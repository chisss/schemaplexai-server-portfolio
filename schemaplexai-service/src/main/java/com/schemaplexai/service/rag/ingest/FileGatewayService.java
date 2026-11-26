package com.schemaplexai.service.rag.ingest;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文件摄入网关（第一道安全防线）。
 *
 * <p>职责边界：只做"能不能进、进来按哪条路解析"的判定，<b>不做解析、不做向量化</b>。
 * 这样网关可以被上传接口同步调用且保持毫秒级，重的活留给异步摄入线程池。
 *
 * <p>顺序即防线顺序：容量 → 后缀黑名单 → MIME 嗅探（不信后缀）→ 加密检测 →
 * 分类与开关 → 前置合规。任一步失败立即返回，绝不"先解析再拦"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileGatewayService {

    private static final Tika TIKA = new Tika();

    /** 嗅探只需要文件头，避免把大文件全读进内存 */
    static final int HEADER_BYTES = 64 * 1024;

    /** 加密 PDF 的 /Encrypt 通常在文件尾部 trailer，因此尾部也需要采样 */
    static final int TAIL_BYTES = 8 * 1024;

    /** 前置合规扫描的文本预览上限 */
    static final int TEXT_PREVIEW_LIMIT = 32 * 1024;

    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final ComplianceScanner complianceScanner;

    /**
     * 判定上传文件。
     *
     * @param fileName    原始文件名（仅作弱提示）
     * @param content     文件内容（网关内部只取头部做嗅探、尾部查加密标记）
     * @param textPreview 轻量文本预览（文本类文件可传入，二进制可为 null）
     * @param settings    租户运行时配置
     */
    public GatewayDecision inspect(String fileName,
                                   byte[] content,
                                   String textPreview,
                                   RagRuntimeSettings settings) {
        if (content == null || content.length == 0) {
            return GatewayDecision.reject(GatewayDecision.REJECT_EMPTY, "文件为空");
        }
        long sizeBytes = content.length;
        byte[] headerBytes = headerOf(content);

        long maxBytes = Math.max(1, settings.getMaxFileSizeMb()) * BYTES_PER_MB;
        if (sizeBytes > maxBytes) {
            return GatewayDecision.reject(GatewayDecision.REJECT_TOO_LARGE,
                    "文件大小 " + toMb(sizeBytes) + "MB 超过租户上限 " + settings.getMaxFileSizeMb() + "MB");
        }

        String extension = IngestFileTypes.extensionOf(fileName);
        if (IngestFileTypes.BLOCKED_EXTENSIONS.contains(extension)) {
            return GatewayDecision.reject(GatewayDecision.REJECT_BLOCKED_TYPE,
                    "不支持的文件类型（可执行/压缩/安装包）: ." + extension);
        }

        String detectedMime = detectMime(headerBytes, fileName);
        if (isBlockedMime(detectedMime)) {
            return GatewayDecision.reject(GatewayDecision.REJECT_BLOCKED_TYPE,
                    "不支持的文件类型（实际内容为 " + detectedMime + "）");
        }

        if (IngestFileTypes.MIME_PDF.equalsIgnoreCase(detectedMime) && looksEncryptedPdf(content)) {
            return GatewayDecision.reject(GatewayDecision.REJECT_ENCRYPTED, "加密 PDF 不允许摄入，请先解密");
        }

        List<String> warnings = new ArrayList<>();
        if (StringUtils.hasText(extension) && !extensionMatchesMime(extension, detectedMime)) {
            warnings.add("扩展名 ." + extension + " 与内容类型 " + detectedMime + " 不一致，已按内容类型处理");
        }

        IngestCategory category = classify(extension, detectedMime);
        if (category == IngestCategory.CODE && !settings.isIngestCodeEnabled()) {
            return GatewayDecision.reject(GatewayDecision.REJECT_CATEGORY_DISABLED, "该租户未开启代码文件摄入");
        }
        if (category == IngestCategory.EXCEL && !settings.isIngestExcelEnabled()) {
            return GatewayDecision.reject(GatewayDecision.REJECT_CATEGORY_DISABLED, "该租户未开启 Excel 摄入");
        }
        if (category == IngestCategory.IMAGE && !settings.isOcrEnabled()) {
            return GatewayDecision.reject(GatewayDecision.REJECT_CATEGORY_DISABLED,
                    "OCR 已关闭，无法处理图片/扫描件");
        }

        ComplianceScanner.ScanResult scan = complianceScanner.scan(
                truncate(textPreview), "gateway:" + (fileName == null ? "-" : fileName));
        warnings.addAll(scan.warnings());
        if (scan.blocking()) {
            return new GatewayDecision(false, category, detectedMime, null,
                    GatewayDecision.REJECT_COMPLIANCE,
                    "内容合规扫描未通过：" + scan.blockSummary(), warnings, scan.counters());
        }

        String parserId = resolveParserId(category);
        return GatewayDecision.accept(category, detectedMime, parserId, warnings, scan.counters());
    }

    /** 分类：内容优先，后缀只在"纯文本无法自证"时做提示 */
    IngestCategory classify(String extension, String detectedMime) {
        if (IngestFileTypes.isImageMime(detectedMime) || IngestFileTypes.IMAGE_EXTENSIONS.contains(extension)) {
            return IngestCategory.IMAGE;
        }
        if (IngestFileTypes.isExcelMime(detectedMime) || IngestFileTypes.EXCEL_EXTENSIONS.contains(extension)) {
            return IngestCategory.EXCEL;
        }
        if (IngestFileTypes.isCodeMime(detectedMime)) {
            return IngestCategory.CODE;
        }
        // 纯文本无法从内容区分源码与普通文本，此时后缀是唯一可用信号
        if (IngestFileTypes.MIME_TEXT_PLAIN.equalsIgnoreCase(detectedMime)
                && IngestFileTypes.CODE_EXTENSIONS.contains(extension)) {
            return IngestCategory.CODE;
        }
        return IngestCategory.DOCUMENT;
    }

    private String resolveParserId(IngestCategory category) {
        return switch (category) {
            case EXCEL -> GatewayDecision.PARSER_EXCEL;
            case IMAGE -> GatewayDecision.PARSER_OCR;
            case CODE, DOCUMENT -> GatewayDecision.PARSER_TIKA;
        };
    }

    /**
     * MIME 嗅探。捕获 Throwable 而不是 Exception：Tika 的容器探测链依赖多个第三方解析器，
     * 依赖冲突时会抛 {@link NoSuchMethodError} 这类 Error，若放出去会让整个上传接口 500。
     * 这里的原则是"嗅探失败不要影响上传判定"，退回二进制类型由后续分类兜底。
     */
    String detectMime(byte[] headerBytes, String fileName) {
        try {
            return TIKA.detect(headerBytes, fileName);
        } catch (Throwable t) {
            log.warn("MIME 嗅探失败，按二进制处理: fileName={}, error={}", fileName, t.toString());
            return "application/octet-stream";
        }
    }

    private boolean isBlockedMime(String mime) {
        if (!StringUtils.hasText(mime)) {
            return false;
        }
        String normalized = mime.toLowerCase(Locale.ROOT);
        return IngestFileTypes.BLOCKED_MIME_TYPES.stream().anyMatch(normalized::startsWith);
    }

    /**
     * 加密 PDF 轻量识别：不解析全文，采样文件头与尾部查找 /Encrypt。
     *
     * <p>必须看尾部：PDF 的加密字典写在 trailer 里，只在头部找会漏掉绝大多数加密文件。
     * 这里宁可误判为"需解密"，也不能把加密件送进解析器。
     */
    boolean looksEncryptedPdf(byte[] content) {
        String head = new String(content, 0, Math.min(content.length, TAIL_BYTES), StandardCharsets.ISO_8859_1);
        if (head.contains("/Encrypt")) {
            return true;
        }
        if (content.length > TAIL_BYTES) {
            int tailStart = content.length - TAIL_BYTES;
            String tail = new String(content, tailStart, TAIL_BYTES, StandardCharsets.ISO_8859_1);
            return tail.contains("/Encrypt");
        }
        return false;
    }

    private byte[] headerOf(byte[] content) {
        if (content.length <= HEADER_BYTES) {
            return content;
        }
        byte[] header = new byte[HEADER_BYTES];
        System.arraycopy(content, 0, header, 0, HEADER_BYTES);
        return header;
    }

    private boolean extensionMatchesMime(String extension, String mime) {
        if (!StringUtils.hasText(mime)) {
            return true;
        }
        boolean extIsImage = IngestFileTypes.IMAGE_EXTENSIONS.contains(extension);
        boolean extIsExcel = IngestFileTypes.EXCEL_EXTENSIONS.contains(extension);
        boolean extIsCode = IngestFileTypes.CODE_EXTENSIONS.contains(extension);
        boolean extIsPdf = IngestFileTypes.PDF_EXTENSIONS.contains(extension);
        boolean extIsWord = IngestFileTypes.WORD_EXTENSIONS.contains(extension);
        boolean extIsText = IngestFileTypes.TEXT_EXTENSIONS.contains(extension);

        if (extIsImage) {
            return IngestFileTypes.isImageMime(mime);
        }
        if (extIsExcel) {
            return IngestFileTypes.isExcelMime(mime) || "text/csv".equalsIgnoreCase(mime)
                    || IngestFileTypes.MIME_TEXT_PLAIN.equalsIgnoreCase(mime) || "application/zip".equalsIgnoreCase(mime);
        }
        if (extIsPdf) {
            return IngestFileTypes.MIME_PDF.equalsIgnoreCase(mime);
        }
        if (extIsWord) {
            return mime.toLowerCase(Locale.ROOT).contains("word")
                    || mime.toLowerCase(Locale.ROOT).contains("opendocument.text");
        }
        if (extIsCode || extIsText) {
            return mime.toLowerCase(Locale.ROOT).startsWith("text/")
                    || IngestFileTypes.isCodeMime(mime)
                    || "application/json".equalsIgnoreCase(mime)
                    || "application/xml".equalsIgnoreCase(mime);
        }
        return true;
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= TEXT_PREVIEW_LIMIT ? text : text.substring(0, TEXT_PREVIEW_LIMIT);
    }

    private String toMb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (double) BYTES_PER_MB);
    }
}

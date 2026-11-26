package com.schemaplexai.service.rag.ingest.parse;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.ingest.GatewayDecision;
import com.schemaplexai.service.rag.ingest.IngestCategory;
import com.schemaplexai.service.rag.ingest.IngestFileTypes;
import com.schemaplexai.service.rag.ingest.ocr.OcrClient;
import com.schemaplexai.service.rag.ingest.ocr.OcrException;
import com.schemaplexai.service.rag.ingest.ocr.OcrRequest;
import com.schemaplexai.service.rag.ingest.ocr.OcrResult;
import com.schemaplexai.service.rag.ingest.ocr.PdfPageRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 扫描件/图片 OCR 解析。
 *
 * <p>三条硬约束（对应设计稿第六章风险管控）：
 * <ol>
 *   <li><b>算力隔离</b>：按租户配置的信号量限流，OCR 再堵也不会抢占问答推理的模型资源；</li>
 *   <li><b>质量兜底</b>：识别文本低于 {@code ocrMinTextChars} 或置信度过低，直接判解析失败，
 *       进人工复核，而不是把一堆乱码沉淀成向量；</li>
 *   <li><b>不静默出境</b>：外部 OCR API 只在租户显式选择 {@code ocrMode=api} 时使用。</li>
 * </ol>
 */
@Slf4j
@Component
public class MultimodalOcrParseHandler implements DocumentParseHandler {

    /** 等待 OCR 并发名额的最长时间 */
    static final long ACQUIRE_TIMEOUT_SECONDS = 30;

    /** 置信度下限：低于该值视为识别失败 */
    static final double MIN_CONFIDENCE = 0.5D;

    private final List<OcrClient> clients;
    private final ConcurrentMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    public MultimodalOcrParseHandler(List<OcrClient> clients) {
        this.clients = clients == null ? List.of() : List.copyOf(clients);
    }

    @Override
    public boolean supports(IngestCategory category) {
        return category == IngestCategory.IMAGE;
    }

    @Override
    public boolean supportsScannedFallback() {
        return true;
    }

    @Override
    public ParsedDocument parse(ParseContext context) {
        RagRuntimeSettings settings = context.settings();
        if (settings == null || !settings.isOcrEnabled()) {
            throw new DocumentParseException(DocumentParseException.OCR_FAILED,
                    "OCR 已关闭，无法处理图片/扫描件");
        }

        OcrClient client = resolveClient(settings);
        Semaphore semaphore = semaphoreFor(settings);
        boolean acquired = false;
        long startMs = System.currentTimeMillis();
        try {
            acquired = semaphore.tryAcquire(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new DocumentParseException(DocumentParseException.OCR_FAILED,
                        "OCR 并发已满（上限 " + settings.getOcrMaxConcurrency() + "），请稍后重试");
            }

            List<byte[]> pages = resolvePageImages(context);
            if (pages.isEmpty()) {
                throw new DocumentParseException(DocumentParseException.OCR_FAILED, "没有可识别的页面图像");
            }

            List<String> pageTexts = new ArrayList<>(pages.size());
            double minConfidence = 1.0D;
            for (int i = 0; i < pages.size(); i++) {
                OcrResult result = client.recognize(new OcrRequest(
                        pages.get(i), "image/png", context.fileName(), i, settings.getOcrModelConfigId()));
                minConfidence = Math.min(minConfidence, result.confidence());
                if (result.hasText()) {
                    pageTexts.add(result.text().trim());
                }
            }

            String text = TextNormalizer.normalize(String.join("\n\n", pageTexts));
            if (text.length() < Math.max(1, settings.getOcrMinTextChars())) {
                throw new DocumentParseException(DocumentParseException.OCR_LOW_CONFIDENCE,
                        "OCR 识别文本仅 " + text.length() + " 字符，低于阈值 "
                                + settings.getOcrMinTextChars() + "，转人工复核");
            }
            if (minConfidence < MIN_CONFIDENCE) {
                throw new DocumentParseException(DocumentParseException.OCR_LOW_CONFIDENCE,
                        "OCR 置信度 " + minConfidence + " 低于下限 " + MIN_CONFIDENCE + "，转人工复核");
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("parser", GatewayDecision.PARSER_OCR);
            metadata.put("ocr_source", client.mode());
            metadata.put("ocr_pages", pages.size());
            metadata.put("ocr_confidence", minConfidence);
            metadata.put("ocr_duration_ms", System.currentTimeMillis() - startMs);
            metadata.put("charCount", text.length());

            log.info("OCR 解析完成: fileName={}, pages={}, chars={}, durationMs={}",
                    context.fileName(), pages.size(), text.length(), System.currentTimeMillis() - startMs);
            return new ParsedDocument(text, GatewayDecision.PARSER_OCR, IngestCategory.IMAGE,
                    metadata, List.of(), false);
        } catch (DocumentParseException e) {
            throw e;
        } catch (OcrException e) {
            throw new DocumentParseException(DocumentParseException.OCR_FAILED, e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentParseException(DocumentParseException.OCR_FAILED, "OCR 等待被中断", e);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private OcrClient resolveClient(RagRuntimeSettings settings) {
        String mode = settings.getOcrMode();
        return clients.stream()
                .filter(client -> client.mode().equalsIgnoreCase(mode))
                .findFirst()
                .orElseThrow(() -> new DocumentParseException(DocumentParseException.OCR_FAILED,
                        "OCR 模式 " + mode + " 没有可用的客户端实现"));
    }

    private Semaphore semaphoreFor(RagRuntimeSettings settings) {
        int permits = Math.max(1, settings.getOcrMaxConcurrency());
        String key = (settings.getTenantId() == null ? "default" : settings.getTenantId()) + "|" + permits;
        return semaphores.computeIfAbsent(key, ignored -> new Semaphore(permits, true));
    }

    /** 图片直接识别；扫描 PDF 先栅格化成多页 PNG */
    private List<byte[]> resolvePageImages(ParseContext context) {
        boolean pdf = IngestFileTypes.MIME_PDF.equalsIgnoreCase(context.mimeType())
                || IngestFileTypes.PDF_EXTENSIONS.contains(IngestFileTypes.extensionOf(context.fileName()));
        if (pdf) {
            return PdfPageRenderer.render(context.content());
        }
        if (!StringUtils.hasText(context.mimeType()) || !IngestFileTypes.isImageMime(context.mimeType())) {
            throw new DocumentParseException(DocumentParseException.OCR_FAILED,
                    "OCR 仅支持图片与扫描 PDF，当前类型: " + context.mimeType());
        }
        return List.of(context.content());
    }
}

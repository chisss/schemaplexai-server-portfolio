package com.schemaplexai.service.rag.ingest;

import java.util.Locale;
import java.util.Set;

/**
 * 文件扩展名与 MIME 常量表。
 *
 * <p>设计稿要求"不依赖后缀判断类型"，因此这里的后缀集合只做两件事：
 * <ol>
 *   <li>黑名单提前拦截（MZ 头、PK 头之外的低成本兜底）；</li>
 *   <li>Tika 判定为 {@code text/plain} 时，作为代码/表格的弱提示（纯文本无法靠内容区分）。</li>
 * </ol>
 * 真正的类型判定始终以 Tika 嗅探结果为准。
 */
public final class IngestFileTypes {

    private IngestFileTypes() {
    }

    /** 直接拒绝：可执行、动态库、安装包、压缩包（压缩包需解压炸弹防护，一期不支持） */
    public static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "dll", "so", "dylib", "bat", "cmd", "com", "msi", "scr", "vbs", "ps1",
            "apk", "ipa", "jar", "war", "bin", "zip", "rar", "7z", "tar", "gz", "bz2", "xz");

    public static final Set<String> BLOCKED_MIME_TYPES = Set.of(
            "application/x-msdownload",
            "application/x-dosexec",
            "application/x-msdos-program",
            "application/x-executable",
            "application/x-sharedlib",
            "application/java-archive",
            "application/zip",
            "application/x-rar-compressed",
            "application/x-7z-compressed",
            "application/x-tar",
            "application/gzip",
            "application/vnd.android.package-archive",
            "application/x-msi");

    public static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "scala", "groovy", "py", "js", "jsx", "ts", "tsx", "go", "rs",
            "c", "h", "cc", "cpp", "hpp", "cs", "rb", "php", "swift", "m", "sh", "zsh", "sql",
            "vue", "svelte", "lua", "pl", "r", "dart", "gradle", "proto", "thrift");

    public static final Set<String> EXCEL_EXTENSIONS = Set.of("xlsx", "xls", "xlsm", "xltx", "xltm", "csv");

    public static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "webp", "bmp", "tif", "tiff", "gif", "heic");

    public static final Set<String> WORD_EXTENSIONS = Set.of("doc", "docx", "rtf", "odt");

    public static final Set<String> PDF_EXTENSIONS = Set.of("pdf");

    public static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "markdown", "log", "html", "htm", "xml", "json", "yaml", "yml");

    /** MIME → 业务分类所需的判断片段 */
    public static final String MIME_PDF = "application/pdf";
    public static final String MIME_TEXT_PLAIN = "text/plain";
    public static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String MIME_XLS = "application/vnd.ms-excel";

    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isImageMime(String mime) {
        return mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    public static boolean isExcelMime(String mime) {
        return mime != null && (MIME_XLSX.equalsIgnoreCase(mime) || MIME_XLS.equalsIgnoreCase(mime));
    }

    public static boolean isCodeMime(String mime) {
        if (mime == null) {
            return false;
        }
        String normalized = mime.toLowerCase(Locale.ROOT);
        return normalized.contains("java-source")
                || normalized.contains("javascript")
                || normalized.contains("typescript")
                || normalized.contains("x-python")
                || normalized.contains("x-sh")
                || normalized.contains("x-csrc")
                || normalized.contains("x-c++src")
                || normalized.contains("x-go")
                || normalized.contains("x-rust")
                || normalized.contains("x-sql");
    }
}

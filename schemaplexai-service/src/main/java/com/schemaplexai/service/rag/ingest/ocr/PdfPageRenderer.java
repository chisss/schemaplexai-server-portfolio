package com.schemaplexai.service.rag.ingest.ocr;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 页面栅格化：把扫描版 PDF 逐页转成 PNG，交给 OCR 客户端识别。
 *
 * <p>页数与 DPI 都设上限：OCR 是按张计费/计算力的，一份 200 页的扫描合同
 * 不能因为"看起来像 PDF"就无脑全渲。
 */
@Slf4j
public final class PdfPageRenderer {

    /** 单文档最多渲染页数 */
    public static final int MAX_PAGES = 20;
    /** 150 DPI 是 OCR 识别率与体积的平衡点 */
    public static final int DPI = 150;

    private PdfPageRenderer() {
    }

    public static List<byte[]> render(byte[] pdfBytes) {
        List<byte[]> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = Math.min(document.getNumberOfPages(), MAX_PAGES);
            for (int i = 0; i < pageCount; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, DPI, ImageType.RGB);
                try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    ImageIO.write(image, "png", out);
                    pages.add(out.toByteArray());
                }
            }
            if (document.getNumberOfPages() > MAX_PAGES) {
                log.info("扫描 PDF 共 {} 页，按上限只渲染前 {} 页", document.getNumberOfPages(), MAX_PAGES);
            }
        } catch (Exception e) {
            throw new OcrException("PDF_RENDER_FAILED", "扫描 PDF 栅格化失败: " + e.getMessage(), e);
        }
        return pages;
    }
}

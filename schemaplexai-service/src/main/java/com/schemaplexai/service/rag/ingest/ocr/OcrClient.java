package com.schemaplexai.service.rag.ingest.ocr;

/**
 * OCR 客户端抽象。
 *
 * <p>两条实现路径对应设计稿的"双模式可选"：
 * <ul>
 *   <li>{@code multimodal}：平台内部多模态模型（内网闭环，首选）；</li>
 *   <li>{@code api}：外部 OCR API（默认关闭，金融场景需评估数据出境）。</li>
 * </ul>
 * 刻意<b>不做</b>跨模式自动降级：内部模型过载时静默把敏感文档发给外部 API，
 * 在金融场景是不可接受的行为。降级必须由租户显式切换配置。
 */
public interface OcrClient {

    /** 对应 {@code ocrMode} 取值 */
    String mode();

    OcrResult recognize(OcrRequest request);
}

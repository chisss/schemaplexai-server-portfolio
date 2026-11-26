package com.schemaplexai.service.rag.ingest.parse;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.ingest.IngestCategory;
import com.schemaplexai.service.rag.ingest.ocr.OcrClient;
import com.schemaplexai.service.rag.ingest.ocr.OcrRequest;
import com.schemaplexai.service.rag.ingest.ocr.OcrResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OCR 解析：模式选择、低质量拦截、并发隔离与名额释放。
 */
class MultimodalOcrParseHandlerTest {

    private static final byte[] PNG_BYTES = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @Test
    @DisplayName("图片走多模态 OCR，元数据标记来源与页数")
    void shouldRecognizeImage() {
        MultimodalOcrParseHandler handler = new MultimodalOcrParseHandler(List.of(
                stubClient("multimodal", "现金贷产品的年化利率区间为 3.6%~18%，额度上限 5 万元。", 1.0)));

        ParsedDocument parsed = handler.parse(context(settings("multimodal")));

        assertThat(parsed.hasText()).isTrue();
        assertThat(parsed.parserId()).isEqualTo("multimodal-ocr");
        assertThat(parsed.category()).isEqualTo(IngestCategory.IMAGE);
        assertThat(parsed.metadata()).containsEntry("ocr_source", "multimodal");
        assertThat(parsed.metadata()).containsEntry("ocr_pages", 1);
    }

    @Test
    @DisplayName("识别文本低于阈值判失败，不把乱码沉淀成向量")
    void shouldFailWhenTextTooShort() {
        MultimodalOcrParseHandler handler = new MultimodalOcrParseHandler(List.of(
                stubClient("multimodal", "模糊", 1.0)));

        assertThatThrownBy(() -> handler.parse(context(settings("multimodal"))))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("低于阈值")
                .extracting(e -> ((DocumentParseException) e).getCode())
                .isEqualTo(DocumentParseException.OCR_LOW_CONFIDENCE);
    }

    @Test
    @DisplayName("置信度低于下限同样判失败")
    void shouldFailWhenConfidenceTooLow() {
        MultimodalOcrParseHandler handler = new MultimodalOcrParseHandler(List.of(
                stubClient("multimodal", "一段看起来挺长但模型自己都没把握的识别文本内容", 0.2)));

        assertThatThrownBy(() -> handler.parse(context(settings("multimodal"))))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("置信度");
    }

    @Test
    @DisplayName("OCR 关闭时直接失败，不尝试调用模型")
    void shouldFailWhenOcrDisabled() {
        AtomicInteger calls = new AtomicInteger();
        OcrClient client = new OcrClient() {
            @Override
            public String mode() {
                return "multimodal";
            }

            @Override
            public OcrResult recognize(OcrRequest request) {
                calls.incrementAndGet();
                return new OcrResult("x", 1.0, "multimodal");
            }
        };
        MultimodalOcrParseHandler handler = new MultimodalOcrParseHandler(List.of(client));

        assertThatThrownBy(() -> handler.parse(context(settings("disabled"))))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("OCR 已关闭");
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("指定 api 模式但没有对应客户端时报明确错误")
    void shouldFailWhenClientMissingForMode() {
        MultimodalOcrParseHandler handler = new MultimodalOcrParseHandler(List.of(
                stubClient("multimodal", "文本", 1.0)));

        assertThatThrownBy(() -> handler.parse(context(settings("api"))))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("没有可用的客户端");
    }

    @Test
    @DisplayName("调用失败后并发名额必须释放，否则第二次永远拿不到许可")
    void shouldReleasePermitAfterFailure() {
        AtomicInteger calls = new AtomicInteger();
        OcrClient failing = new OcrClient() {
            @Override
            public String mode() {
                return "multimodal";
            }

            @Override
            public OcrResult recognize(OcrRequest request) {
                calls.incrementAndGet();
                throw new com.schemaplexai.service.rag.ingest.ocr.OcrException("OCR_CALL_FAILED", "模拟超时");
            }
        };
        MultimodalOcrParseHandler handler = new MultimodalOcrParseHandler(List.of(failing));
        RagRuntimeSettings settings = settingsWithConcurrency("multimodal", 1);

        assertThatThrownBy(() -> handler.parse(context(settings)))
                .isInstanceOf(DocumentParseException.class);
        assertThatThrownBy(() -> handler.parse(context(settings)))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("模拟超时");
        assertThat(calls.get()).isEqualTo(2);
    }

    private OcrClient stubClient(String mode, String text, double confidence) {
        return new OcrClient() {
            @Override
            public String mode() {
                return mode;
            }

            @Override
            public OcrResult recognize(OcrRequest request) {
                return new OcrResult(text, confidence, mode);
            }
        };
    }

    private ParseContext context(RagRuntimeSettings settings) {
        return new ParseContext("扫描件.png", "image/png", IngestCategory.IMAGE, PNG_BYTES, settings);
    }

    private RagRuntimeSettings settings(String ocrMode) {
        return settingsWithConcurrency(ocrMode, 2);
    }

    private RagRuntimeSettings settingsWithConcurrency(String ocrMode, int concurrency) {
        return RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .ocrMode(ocrMode)
                .ocrModelConfigId("model-1")
                .ocrMaxConcurrency(concurrency)
                .ocrMinTextChars(10)
                .build();
    }
}

package com.schemaplexai.service.rag.ingest;

import com.schemaplexai.service.rag.RagDefaults;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关判定：后缀伪装、黑名单、容量、分类、开关、前置合规。
 */
class FileGatewayServiceTest {

    private FileGatewayService gateway;

    @BeforeEach
    void setUp() {
        gateway = new FileGatewayService(new ComplianceScanner());
    }

    private RagRuntimeSettings settings() {
        return settingsBuilder().build();
    }

    private RagRuntimeSettings.RagRuntimeSettingsBuilder settingsBuilder() {
        return RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .enabled(true)
                .collectionName("sf_context_items")
                .chunkSize(RagDefaults.CHUNK_SIZE)
                .chunkOverlap(RagDefaults.CHUNK_OVERLAP)
                .retrievalTopK(RagDefaults.RETRIEVAL_TOP_K)
                .retrievalMinScore(RagDefaults.RETRIEVAL_MIN_SCORE)
                .embeddingDimension(1024)
                .maxFileSizeMb(10)
                .ingestCodeEnabled(true)
                .ingestExcelEnabled(true)
                .ocrMode(RagDefaults.OCR_MODE_MULTIMODAL)
                .ocrMaxConcurrency(2)
                .ocrMinTextChars(20)
                .sectionSplitEnabled(true)
                .codeAstSplitEnabled(true)
                .tableBlockSplitEnabled(true)
                .chunkAnchorEnabled(true)
                .bm25Enabled(true)
                .bm25TopK(20)
                .vectorTopK(20)
                .prefilterRequired(true)
                .rrfK(60)
                .fusionTopN(20)
                .rerankPolicy(RagDefaults.RERANK_POLICY_AUTO)
                .rerankTimeoutMs(1500)
                .maxFilesPerContext(2)
                .entityOverlapFilterEnabled(true)
                .contextTokenBudget(4000)
                .keywordFastPathEnabled(true)
                .retrievalCacheEnabled(true)
                .retrievalCacheTtlSeconds(300)
                .citationRequired(true)
                .hitlOnLowFaithfulness(true)
                .faithfulnessThreshold(0.7)
                .evalRecallK(20);
    }

    @Test
    @DisplayName("PE 可执行文件改后缀为 .pdf 仍被 MIME 嗅探拦下")
    void shouldRejectExecutableDisguisedAsPdf() {
        byte[] mzBytes = new byte[256];
        mzBytes[0] = 'M';
        mzBytes[1] = 'Z';
        mzBytes[2] = (byte) 0x90;

        GatewayDecision decision = gateway.inspect("合同.pdf", mzBytes, null, settings());

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.rejectCode()).isEqualTo(GatewayDecision.REJECT_BLOCKED_TYPE);
    }

    @Test
    @DisplayName("压缩包后缀直接拒绝，不进入嗅探")
    void shouldRejectArchiveByExtension() {
        byte[] bytes = "PK\u0003\u0004fake".getBytes(StandardCharsets.ISO_8859_1);

        GatewayDecision decision = gateway.inspect("资料.zip", bytes, null, settings());

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.rejectCode()).isEqualTo(GatewayDecision.REJECT_BLOCKED_TYPE);
    }

    @Test
    @DisplayName("超过租户容量上限直接拒绝")
    void shouldRejectOversizedFile() {
        byte[] bytes = "%PDF-1.7 hello".getBytes(StandardCharsets.ISO_8859_1);

        GatewayDecision decision = gateway.inspect("过大.pdf", new byte[11 * 1024 * 1024], null, settings());

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.rejectCode()).isEqualTo(GatewayDecision.REJECT_TOO_LARGE);
    }

    @Test
    @DisplayName("加密 PDF 在前置阶段拦住，不送解析器")
    void shouldRejectEncryptedPdf() {
        // /Encrypt 在文件尾部 trailer，头部只有正常 PDF 头，验证尾部采样生效
        String body = "%PDF-1.7\n1 0 obj<< /Type /Catalog >>endobj\n".repeat(200);
        String pdf = body + "trailer\n<< /Encrypt 12 0 R >>\n%%EOF";
        byte[] bytes = pdf.getBytes(StandardCharsets.ISO_8859_1);

        GatewayDecision decision = gateway.inspect("加密.pdf", bytes, null, settings());

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.rejectCode()).isEqualTo(GatewayDecision.REJECT_ENCRYPTED);
    }

    @Test
    @DisplayName("源码文件分类为 CODE，走 tika 解析器")
    void shouldClassifyJavaSourceAsCode() {
        String source = "public class PaymentService {\n    void pay() {}\n}";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);

        GatewayDecision decision = gateway.inspect("PaymentService.java", bytes, source, settings());

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.category()).isEqualTo(IngestCategory.CODE);
        assertThat(decision.parserId()).isEqualTo(GatewayDecision.PARSER_TIKA);
    }

    @Test
    @DisplayName("Excel 分类为 EXCEL，走 POI 解析器")
    void shouldClassifyXlsxAsExcel() throws Exception {
        byte[] bytes = buildXlsx();

        GatewayDecision decision = gateway.inspect("额度表.xlsx", bytes, null, settings());

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.category()).isEqualTo(IngestCategory.EXCEL);
        assertThat(decision.parserId()).isEqualTo(GatewayDecision.PARSER_EXCEL);
    }

    @Test
    @DisplayName("图片分类为 IMAGE，走多模态 OCR")
    void shouldClassifyPngAsImage() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0x0D, 'I', 'H', 'D', 'R',
                0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0};

        GatewayDecision decision = gateway.inspect("扫描件.png", png, null, settings());

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.category()).isEqualTo(IngestCategory.IMAGE);
        assertThat(decision.parserId()).isEqualTo(GatewayDecision.PARSER_OCR);
    }

    @Test
    @DisplayName("租户关闭代码摄入后源码被拒")
    void shouldRejectCodeWhenDisabled() {
        String source = "def train(): pass";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        RagRuntimeSettings settings = settingsBuilder().ingestCodeEnabled(false).build();

        GatewayDecision decision = gateway.inspect("train.py", bytes, source, settings);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.rejectCode()).isEqualTo(GatewayDecision.REJECT_CATEGORY_DISABLED);
    }

    @Test
    @DisplayName("OCR 关闭时图片被拒")
    void shouldRejectImageWhenOcrDisabled() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        RagRuntimeSettings settings = settingsBuilder().ocrMode(RagDefaults.OCR_MODE_DISABLED).build();

        GatewayDecision decision = gateway.inspect("扫描件.png", png, null, settings);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.rejectCode()).isEqualTo(GatewayDecision.REJECT_CATEGORY_DISABLED);
    }

    @Test
    @DisplayName("批量身份证号在前置合规被拦，且不进入解析")
    void shouldRejectBulkPrivacyData() {
        String text = "客户清单：110101199001011234，110101199001011235，110101199001011236";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        GatewayDecision decision = gateway.inspect("客户清单.txt", bytes, text, settings());

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.rejectCode()).isEqualTo(GatewayDecision.REJECT_COMPLIANCE);
        assertThat(decision.privacyCounters()).containsKey("cn_id_card");
    }

    @Test
    @DisplayName("密级关键词命中即拦截")
    void shouldRejectClassifiedKeyword() {
        String text = "绝密：本次授信审批口径仅限内部使用。";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        GatewayDecision decision = gateway.inspect("授信口径.txt", bytes, text, settings());

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.rejectCode()).isEqualTo(GatewayDecision.REJECT_COMPLIANCE);
    }

    @Test
    @DisplayName("普通参数表不误伤")
    void shouldAcceptPlainDocument() {
        String text = "产品说明\n本产品年化利率 3.6%，期限 12 个月。";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        GatewayDecision decision = gateway.inspect("产品说明.txt", bytes, text, settings());

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.category()).isEqualTo(IngestCategory.DOCUMENT);
    }

    private byte[] buildXlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("额度");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("产品");
            header.createCell(1).setCellValue("额度");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("现金贷");
            data.createCell(1).setCellValue(50000);
            workbook.write(out);
            return out.toByteArray();
        }
    }
}

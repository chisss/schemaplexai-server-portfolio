package com.schemaplexai.service.rag.ingest.split;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.ingest.IngestCategory;
import com.schemaplexai.service.rag.ingest.parse.ParsedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切分路由与三类策略：章节语义、表格块、通用窗口兜底。
 */
class RouterDocumentSplitterTest {

    private final RouterDocumentSplitter router = new RouterDocumentSplitter(
            new SectionSemanticSplitter(), new CodeAstSplitter(), new TableBlockSplitter());

    @Test
    @DisplayName("按章节切分，每个 chunk 带来源锚点与章节路径")
    void shouldSplitBySectionWithAnchor() {
        String text = """
                # 第一章 总则
                本产品面向个人客户，最高额度 5 万元。借款期限 3~36 期。
                ## 1.1 利率说明
                年化利率 3.6% 起，按日计息，提前还款不收取违约金。
                ## 1.2 逾期处理
                逾期后按日收取罚息，罚息利率为借款利率的 1.5 倍。
                """;

        List<RoutedChunk> chunks = router.split(parsed(text, IngestCategory.DOCUMENT), "产品说明.pdf", settings(600));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).text()).startsWith("【来源：产品说明.pdf ›");
        assertThat(chunks.get(0).section()).contains("第一章 总则");
        assertThat(chunks.stream().map(RoutedChunk::section))
                .anyMatch(section -> section.contains("1.2 逾期处理"));
    }

    @Test
    @DisplayName("关闭锚点后不再注入【来源…】，但正文完整")
    void shouldSkipAnchorWhenDisabled() {
        String text = "# 标题\n正文内容，说明产品额度为 5 万元。";

        List<RoutedChunk> chunks = router.split(parsed(text, IngestCategory.DOCUMENT), "a.pdf",
                settingsBuilder(600).chunkAnchorEnabled(false).build());

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).text()).doesNotContain("【来源");
    }

    @Test
    @DisplayName("超长章节按预算切成多块，且不越过 token 预算太多")
    void shouldSplitLongSectionWithinBudget() {
        String sentence = "本条款用于说明借款合同中的权利义务关系以及违约责任承担方式。";
        String text = "# 违约责任\n" + sentence.repeat(120);

        List<RoutedChunk> chunks = router.split(parsed(text, IngestCategory.DOCUMENT), "合同.pdf",
                settings(300));

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(SplitSupport.tokens(chunk.body())).isLessThanOrEqualTo(900));
    }

    @Test
    @DisplayName("Excel Markdown 按表格块切分，每个 chunk 都重复表头")
    void shouldRepeatTableHeaderInEveryChunk() {
        StringBuilder markdown = new StringBuilder("## Sheet: 额度\n\n| 产品 | 额度 | 利率 |\n| --- | --- | --- |\n");
        for (int i = 0; i < 200; i++) {
            markdown.append("| 产品").append(i).append(" | ").append(1000 + i).append(" | 3.6% |\n");
        }

        List<RoutedChunk> chunks = router.split(
                new ParsedDocument(markdown.toString(), "excel-poi", IngestCategory.EXCEL,
                        Map.of(), List.of(), false),
                "额度表.xlsx", settings(300));

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.text()).contains("| 产品 | 额度 | 利率 |");
            assertThat(chunk.text()).contains("## Sheet: 额度");
            assertThat(chunk.section()).isEqualTo("额度");
        });
    }

    @Test
    @DisplayName("代码按 AST 单元切分，单元名进入锚点")
    void shouldSplitCodeByUnit() {
        String code = """
                public class PaymentService {
                    public void payAmount(Long amount) {
                        validate(amount);
                    }

                    public void refund(Long id) {
                        release(id);
                    }
                }
                """;

        List<RoutedChunk> chunks = router.split(
                new ParsedDocument(code, "tika", IngestCategory.CODE, Map.of(), List.of(), false),
                "PaymentService.java", settings(600));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).section()).isEqualTo("PaymentService");
    }

    @Test
    @DisplayName("关闭表格块切分后退回通用窗口切分，但仍产出 chunk")
    void shouldFallbackToWindowWhenTableSplitDisabled() {
        String markdown = "## Sheet: 额度\n\n| 产品 | 额度 |\n| --- | --- |\n| 现金贷 | 50000 |\n";

        List<RoutedChunk> chunks = router.split(
                new ParsedDocument(markdown, "excel-poi", IngestCategory.EXCEL, Map.of(), List.of(), false),
                "额度.xlsx", settingsBuilder(600).tableBlockSplitEnabled(false).build());

        assertThat(chunks).isNotEmpty();
    }

    @Test
    @DisplayName("空文本不产出 chunk")
    void shouldReturnEmptyForBlankText() {
        assertThat(router.split(ParsedDocument.of("   ", "tika", IngestCategory.DOCUMENT), "a.pdf", settings(600)))
                .isEmpty();
    }

    private ParsedDocument parsed(String text, IngestCategory category) {
        return new ParsedDocument(text, "tika", category, Map.of(), List.of(), false);
    }

    private RagRuntimeSettings settings(int chunkSize) {
        return settingsBuilder(chunkSize).build();
    }

    private RagRuntimeSettings.RagRuntimeSettingsBuilder settingsBuilder(int chunkSize) {
        return RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .chunkSize(chunkSize)
                .chunkOverlap(50)
                .sectionSplitEnabled(true)
                .codeAstSplitEnabled(true)
                .tableBlockSplitEnabled(true)
                .chunkAnchorEnabled(true);
    }
}

package com.schemaplexai.service.rag.ingest.split;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代码切分：AST 单元识别、标识符拆分、超长单元二次切分。
 */
class CodeAstSplitterTest {

    private final CodeAstSplitter splitter = new CodeAstSplitter();

    @Test
    @DisplayName("按类/方法切分，单元名用于定位")
    void shouldExtractClassAndMethodUnits() {
        String code = """
                public class OrderService {
                    public void createOrder(Long userId) {
                        check(userId);
                    }

                    private void check(Long userId) {
                        assert userProfiles.containsKey(userId);
                    }
                }
                """;

        List<CodeAstSplitter.CodeUnit> units = splitter.extractUnits(code);

        assertThat(units).isNotEmpty();
        assertThat(units.get(0).name()).isEqualTo("OrderService");
    }

    @Test
    @DisplayName("Python def/class 也能识别")
    void shouldExtractPythonUnits() {
        String code = """
                class RiskEngine:
                    def evaluate(self, order):
                        return order.amount < 1000

                    def reject(self, order):
                        return False
                """;

        List<CodeAstSplitter.CodeUnit> units = splitter.extractUnits(code);

        assertThat(units).isNotEmpty();
        assertThat(units.get(0).name()).isEqualTo("RiskEngine");
    }

    @Test
    @DisplayName("标识符拆分：驼峰与下划线都要能命中")
    void shouldSplitIdentifiersForBm25() {
        assertThat(splitter.identifierSplit("public void payAmount(Long x) {}"))
                .contains("pay Amount");
        assertThat(splitter.identifierSplit("def pay_amount(): pass"))
                .contains("pay amount");
    }

    @Test
    @DisplayName("超长方法按预算二次切分，不产出一个巨型 chunk")
    void shouldSplitLongMethod() {
        StringBuilder code = new StringBuilder("public class Big {\n    public void run() {\n");
        for (int i = 0; i < 400; i++) {
            code.append("        doStep").append(i).append("();\n");
        }
        code.append("    }\n}\n");

        List<RoutedChunk> chunks = splitter.split(code.toString(), "Big.java", settings(200));

        assertThat(chunks.size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("每个 chunk 的 bm25Text 与正文分离，正文保留原始标识符")
    void shouldKeepBodyRawAndBm25Split() {
        String code = "public class PaymentGateway {\n    void payAmount() {}\n}\n";

        List<RoutedChunk> chunks = splitter.split(code, "PaymentGateway.java", settings(600));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).text()).contains("payAmount");
        assertThat(chunks.get(0).bm25Text()).contains("pay Amount");
    }

    private RagRuntimeSettings settings(int chunkSize) {
        return RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .chunkSize(chunkSize)
                .chunkOverlap(40)
                .chunkAnchorEnabled(true)
                .build();
    }
}

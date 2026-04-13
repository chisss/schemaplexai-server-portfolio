package com.schemaplexai.service.integration.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuDocDeliveryServiceTest {

    @Test
    void shouldConvertMarkdownAndCreateFeishuDocBlocks() throws Exception {
        MockFeishuDocServer server = new MockFeishuDocServer();
        try {
            FeishuDocDeliveryService service = new FeishuDocDeliveryService(new OkHttpClient(), new ObjectMapper());
            String markdown = """
                    # 菲律宾信贷系统营销文案包

                    ## 产物概览

                    已生成菲律宾信贷系统 AB 版营销文案。

                    - A：利益导向版
                    - B：信任导向版

                    ---

                    > 需披露 APR 与放款条件

                    ```markdown
                    CTA: 立即申请
                    ```
                    """;

            FeishuDocDeliveryService.FeishuDocDeliveryResult result = service.deliver(
                    Map.of(
                            "base_url", server.baseUrl(),
                            "app_id", "cli_mock",
                            "app_secret", "mock_secret"
                    ),
                    "菲律宾信贷系统营销文案包",
                    markdown
            );

            assertThat(result.documentId()).isEqualTo("doxcn-test-doc");
            assertThat(result.documentUrl()).isEqualTo("https://feishu.cn/docx/doxcn-test-doc");
            assertThat(server.createDocumentRequests).hasSize(1);
            assertThat(server.rootChildrenRequests()).hasSize(1);
            assertThat(server.rootDescendantRequests()).isEmpty();

            Map<String, Object> createBody = server.createDocumentRequests.getFirst();
            assertThat(createBody.get("title")).isEqualTo("菲律宾信贷系统营销文案包");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) server.rootChildrenRequests().getFirst().body().get("children");
            assertThat(children).hasSize(8);
            assertThat(children.get(0).get("block_type")).isEqualTo(3);
            assertThat(children.get(0)).containsKey("heading1");
            assertThat(children.get(1).get("block_type")).isEqualTo(4);
            assertThat(children.get(2).get("block_type")).isEqualTo(2);
            assertThat(children.get(3).get("block_type")).isEqualTo(12);
            assertThat(children.get(4).get("block_type")).isEqualTo(12);
            assertThat(children.get(5).get("block_type")).isEqualTo(22);
            assertThat(children.get(6).get("block_type")).isEqualTo(15);
            assertThat(children.get(7).get("block_type")).isEqualTo(14);
        } finally {
            server.close();
        }
    }

    @Test
    void shouldSplitBlocksIntoBatchesOfFifty() throws Exception {
        MockFeishuDocServer server = new MockFeishuDocServer();
        try {
            FeishuDocDeliveryService service = new FeishuDocDeliveryService(new OkHttpClient(), new ObjectMapper());
            StringBuilder builder = new StringBuilder("# 批量验证").append(System.lineSeparator()).append(System.lineSeparator());
            for (int index = 1; index <= 55; index++) {
                builder.append("第 ").append(index).append(" 条营销文案").append(System.lineSeparator()).append(System.lineSeparator());
            }

            service.deliver(
                    Map.of(
                            "base_url", server.baseUrl(),
                            "app_id", "cli_mock",
                            "app_secret", "mock_secret"
                    ),
                    "批量写块验证",
                    builder.toString()
            );

            assertThat(server.rootChildrenRequests()).hasSize(2);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> firstBatch = (List<Map<String, Object>>) server.rootChildrenRequests().get(0).body().get("children");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> secondBatch = (List<Map<String, Object>>) server.rootChildrenRequests().get(1).body().get("children");
            assertThat(firstBatch).hasSize(50);
            assertThat(secondBatch).hasSize(6);
        } finally {
            server.close();
        }
    }

    @Test
    void shouldRenderMarkdownTableAsRealFeishuTable() throws Exception {
        MockFeishuDocServer server = new MockFeishuDocServer();
        try {
            FeishuDocDeliveryService service = new FeishuDocDeliveryService(new OkHttpClient(), new ObjectMapper());
            String markdown = """
                    | 公司名称 | 需求信号 |
                    | --- | --- |
                    | Max Titanium | 官网长期销售 BCAA 与谷氨酰胺产品 |
                    | Integralmédica | 公开产品线包含多种氨基酸补剂 |
                    """;

            FeishuDocDeliveryService.FeishuDocDeliveryResult result = service.deliver(
                    Map.of(
                            "base_url", server.baseUrl(),
                            "app_id", "cli_mock",
                            "app_secret", "mock_secret"
                    ),
                    "南美氨基酸线索表",
                    markdown
            );

            assertThat(result.documentId()).isEqualTo("doxcn-test-doc");
            assertThat(result.blockCount()).isEqualTo(1);
            assertThat(server.rootChildrenRequests()).isEmpty();
            assertThat(server.rootDescendantRequests()).hasSize(1);
            assertThat(server.cellChildrenRequests()).isEmpty();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> descendants = (List<Map<String, Object>>) server.rootDescendantRequests().getFirst().body().get("descendants");
            @SuppressWarnings("unchecked")
            List<String> childrenIds = (List<String>) server.rootDescendantRequests().getFirst().body().get("children_id");
            assertThat(childrenIds).hasSize(1);
            assertThat(descendants).hasSize(13);
            assertThat(descendants.getFirst().get("block_type")).isEqualTo(31);
            @SuppressWarnings("unchecked")
            Map<String, Object> table = (Map<String, Object>) descendants.getFirst().get("table");
            @SuppressWarnings("unchecked")
            Map<String, Object> property = (Map<String, Object>) table.get("property");
            assertThat(property.get("row_size")).isEqualTo(3);
            assertThat(property.get("column_size")).isEqualTo(2);
            assertThat(property).doesNotContainKey("merge_info");

            assertDescendantTextContent(server.rootDescendantRequests().getFirst(), "cell_1_text", "公司名称");
            assertDescendantTextContent(server.rootDescendantRequests().getFirst(), "cell_2_text", "需求信号");
            assertDescendantTextContent(server.rootDescendantRequests().getFirst(), "cell_3_text", "Max Titanium");
            assertDescendantTextContent(server.rootDescendantRequests().getFirst(), "cell_4_text", "官网长期销售 BCAA 与谷氨酰胺产品");
            assertDescendantTextContent(server.rootDescendantRequests().getFirst(), "cell_5_text", "Integralmédica");
            assertDescendantTextContent(server.rootDescendantRequests().getFirst(), "cell_6_text", "公开产品线包含多种氨基酸补剂");
        } finally {
            server.close();
        }
    }

    @Test
    void shouldSplitWideMarkdownTableIntoMultipleFeishuTables() throws Exception {
        MockFeishuDocServer server = new MockFeishuDocServer();
        try {
            FeishuDocDeliveryService service = new FeishuDocDeliveryService(new OkHttpClient(), new ObjectMapper());
            String markdown = """
                    | # | 公司名称 | 国家/地区 | 公司简要信息 | 氨基酸需求信号 | 证据链接 | 联系人/部门 | 联系人基本信息 | 联系方式 | 备注 |
                    | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                    | 1 | New Millen | 巴西 | 运动营养品牌 | Matéria Prima 原料板块 | https://newmillen.com.br/materia-prima/ | 商务联系部门 | 官网联系页 | contato@newmillen.com.br | 最强信号 |
                    """;

            service.deliver(
                    Map.of(
                            "base_url", server.baseUrl(),
                            "app_id", "cli_mock",
                            "app_secret", "mock_secret"
                    ),
                    "宽表拆分验证",
                    markdown
            );

            assertThat(server.rootChildrenRequests()).isEmpty();
            assertThat(server.rootDescendantRequests()).hasSize(2);
            assertThat(server.cellChildrenRequests()).isEmpty();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> firstChildren = (List<Map<String, Object>>) server.rootDescendantRequests().get(0).body().get("descendants");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> secondChildren = (List<Map<String, Object>>) server.rootDescendantRequests().get(1).body().get("descendants");
            @SuppressWarnings("unchecked")
            Map<String, Object> firstTableProperty = (Map<String, Object>) ((Map<String, Object>) firstChildren.getFirst().get("table")).get("property");
            @SuppressWarnings("unchecked")
            Map<String, Object> secondTableProperty = (Map<String, Object>) ((Map<String, Object>) secondChildren.getFirst().get("table")).get("property");
            assertThat(firstTableProperty.get("column_size")).isEqualTo(9);
            assertThat(secondTableProperty.get("column_size")).isEqualTo(3);
            assertThat(firstTableProperty).doesNotContainKey("merge_info");
            assertThat(secondTableProperty).doesNotContainKey("merge_info");

            assertDescendantTextContent(server.rootDescendantRequests().get(1), "cell_1_text", "#");
            assertDescendantTextContent(server.rootDescendantRequests().get(1), "cell_2_text", "公司名称");
            assertDescendantTextContent(server.rootDescendantRequests().get(1), "cell_3_text", "备注");
            assertDescendantTextContent(server.rootDescendantRequests().get(1), "cell_4_text", "1");
            assertDescendantTextContent(server.rootDescendantRequests().get(1), "cell_5_text", "New Millen");
            assertDescendantTextContent(server.rootDescendantRequests().get(1), "cell_6_text", "最强信号");
        } finally {
            server.close();
        }
    }

    @SuppressWarnings("unchecked")
    private void assertCellContent(AppendRequest appendRequest, String expectedContent) {
        List<Map<String, Object>> children = (List<Map<String, Object>>) appendRequest.body().get("children");
        assertThat(children).hasSize(1);
        Map<String, Object> textBlock = children.getFirst();
        assertThat(textBlock.get("block_type")).isEqualTo(2);
        Map<String, Object> text = (Map<String, Object>) textBlock.get("text");
        List<Map<String, Object>> elements = (List<Map<String, Object>>) text.get("elements");
        Map<String, Object> firstElement = elements.getFirst();
        Map<String, Object> textRun = (Map<String, Object>) firstElement.get("text_run");
        assertThat(textRun.get("content")).isEqualTo(expectedContent);
    }

    @SuppressWarnings("unchecked")
    private void assertDescendantTextContent(AppendRequest appendRequest, String blockId, String expectedContent) {
        List<Map<String, Object>> descendants = (List<Map<String, Object>>) appendRequest.body().get("descendants");
        Map<String, Object> textBlock = descendants.stream()
                .filter(block -> blockId.equals(block.get("block_id")))
                .findFirst()
                .orElseThrow();
        assertThat(textBlock.get("block_type")).isEqualTo(2);
        Map<String, Object> text = (Map<String, Object>) textBlock.get("text");
        List<Map<String, Object>> elements = (List<Map<String, Object>>) text.get("elements");
        Map<String, Object> firstElement = elements.getFirst();
        Map<String, Object> textRun = (Map<String, Object>) firstElement.get("text_run");
        assertThat(textRun.get("content")).isEqualTo(expectedContent);
    }

    private record AppendRequest(String path, Map<String, Object> body) {
    }

    private static final class MockFeishuDocServer implements AutoCloseable {

        private final ObjectMapper objectMapper = new ObjectMapper();
        private final HttpServer server;
        private final List<Map<String, Object>> createDocumentRequests = new ArrayList<>();
        private final List<AppendRequest> appendRequests = new ArrayList<>();
        private Map<String, Object> createdTableBlock = null;
        private List<Map<String, Object>> createdTableCells = List.of();

        private MockFeishuDocServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/open-apis/auth/v3/tenant_access_token/internal", exchange ->
                    writeJson(exchange, Map.of(
                            "code", 0,
                            "tenant_access_token", "tenant-token"
                    )));
            server.createContext("/open-apis/docx/v1/documents", exchange -> {
                createDocumentRequests.add(readBody(exchange));
                writeJson(exchange, Map.of(
                        "code", 0,
                        "msg", "success",
                        "data", Map.of(
                                "document", Map.of(
                                        "document_id", "doxcn-test-doc",
                                        "revision_id", 1,
                                        "title", "菲律宾信贷系统营销文案包"
                                )
                        )
                ));
            });
            server.createContext("/open-apis/docx/v1/documents/doxcn-test-doc/blocks", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    List<Map<String, Object>> items = new ArrayList<>();
                    if (createdTableBlock != null) {
                        items.add(createdTableBlock);
                        items.addAll(createdTableCells);
                    }
                    writeJson(exchange, Map.of(
                            "code", 0,
                            "msg", "success",
                            "data", Map.of(
                                    "items", items,
                                    "has_more", false
                            )
                    ));
                    return;
                }
                Map<String, Object> requestBody = readBody(exchange);
                appendRequests.add(new AppendRequest(path, requestBody));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) requestBody.getOrDefault("children", List.of());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> descendants = (List<Map<String, Object>>) requestBody.getOrDefault("descendants", List.of());
                if (path.endsWith("/blocks/doxcn-test-doc/children") && containsTable(children)) {
                    writeJson(exchange, Map.of(
                            "code", 1770001,
                            "msg", "invalid param"
                    ));
                    return;
                }
                if (path.endsWith("/blocks/doxcn-test-doc/descendant") && hasManualMergeInfo(descendants)) {
                    writeJson(exchange, Map.of(
                            "code", 1770001,
                            "msg", "invalid param"
                    ));
                    return;
                }
                if ((path.endsWith("/blocks/doxcn-test-doc/children") && hasTooManyColumns(children))
                        || (path.endsWith("/blocks/doxcn-test-doc/descendant") && hasTooManyColumns(descendants))) {
                    writeJson(exchange, Map.of(
                            "code", 1770001,
                            "msg", "invalid param"
                    ));
                    return;
                }
                Map<String, Object> responseData = new LinkedHashMap<>();
                responseData.put("document_revision_id", appendRequests.size() + 1);
                responseData.put("client_token", "client-token-" + appendRequests.size());
                if (path.endsWith("/blocks/doxcn-test-doc/descendant") && containsTable(descendants)) {
                    Map<String, Object> tableRequest = descendants.stream()
                            .filter(child -> Integer.valueOf(31).equals(asInteger(child.get("block_type"))))
                            .findFirst()
                            .orElse(Map.of());
                    @SuppressWarnings("unchecked")
                    Map<String, Object> table = (Map<String, Object>) tableRequest.get("table");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> property = table != null ? (Map<String, Object>) table.get("property") : Map.of();
                    int rows = asInteger(property.get("row_size"));
                    int columns = asInteger(property.get("column_size"));
                    createdTableCells = buildTableCells(rows, columns);
                    createdTableBlock = buildTableBlock(rows, columns);
                    responseData.put("block_id_relations", buildBlockIdRelations(descendants));
                    responseData.put("children", List.of(createdTableBlock));
                }
                writeJson(exchange, Map.of(
                        "code", 0,
                        "msg", "success",
                        "data", responseData
                ));
            });
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private List<AppendRequest> rootChildrenRequests() {
            return appendRequests.stream()
                    .filter(request -> request.path().endsWith("/blocks/doxcn-test-doc/children"))
                    .toList();
        }

        private List<AppendRequest> rootDescendantRequests() {
            return appendRequests.stream()
                    .filter(request -> request.path().endsWith("/blocks/doxcn-test-doc/descendant"))
                    .toList();
        }

        private List<AppendRequest> cellChildrenRequests() {
            return appendRequests.stream()
                    .filter(request -> request.path().contains("/blocks/cell_"))
                    .toList();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> readBody(HttpExchange exchange) throws IOException {
            try (InputStream inputStream = exchange.getRequestBody()) {
                byte[] bytes = inputStream.readAllBytes();
                if (bytes.length == 0) {
                    return Map.of();
                }
                return objectMapper.readValue(bytes, LinkedHashMap.class);
            }
        }

        private boolean containsTable(List<Map<String, Object>> children) {
            return children.stream().anyMatch(child -> Integer.valueOf(31).equals(asInteger(child.get("block_type"))));
        }

        @SuppressWarnings("unchecked")
        private boolean hasManualMergeInfo(List<Map<String, Object>> children) {
            return children.stream().anyMatch(child -> {
                if (!Integer.valueOf(31).equals(asInteger(child.get("block_type")))) {
                    return false;
                }
                Map<String, Object> table = (Map<String, Object>) child.get("table");
                Map<String, Object> property = table != null ? (Map<String, Object>) table.get("property") : Map.of();
                return property.containsKey("merge_info");
            });
        }

        @SuppressWarnings("unchecked")
        private boolean hasTooManyColumns(List<Map<String, Object>> children) {
            return children.stream().anyMatch(child -> {
                if (!Integer.valueOf(31).equals(asInteger(child.get("block_type")))) {
                    return false;
                }
                Map<String, Object> table = (Map<String, Object>) child.get("table");
                Map<String, Object> property = table != null ? (Map<String, Object>) table.get("property") : Map.of();
                return asInteger(property.get("column_size")) > 9;
            });
        }

        private List<Map<String, Object>> buildTableCells(int rows, int columns) {
            List<Map<String, Object>> cells = new ArrayList<>();
            for (int index = 0; index < rows * columns; index++) {
                cells.add(Map.of(
                        "block_id", "cell_" + (index + 1),
                        "block_type", 32
                ));
            }
            return cells;
        }

        private Map<String, Object> buildTableBlock(int rows, int columns) {
            return Map.of(
                    "block_id", "tbl_001",
                    "block_type", 31,
                    "children", createdTableCells.stream().map(cell -> cell.get("block_id")).toList(),
                    "parent_id", "doxcn-test-doc",
                    "table", Map.of(
                            "cells", createdTableCells.stream().map(cell -> cell.get("block_id")).toList(),
                            "property", Map.of(
                                    "row_size", rows,
                                    "column_size", columns,
                                    "column_width", buildColumnWidths(columns),
                                    "merge_info", buildMergeInfo(rows, columns)
                            )
                    )
            );
        }

        private List<Map<String, Object>> buildBlockIdRelations(List<Map<String, Object>> descendants) {
            List<Map<String, Object>> relations = new ArrayList<>();
            for (Map<String, Object> descendant : descendants) {
                Object temporaryBlockId = descendant.get("block_id");
                if (temporaryBlockId != null) {
                    relations.add(Map.of(
                            "temporary_block_id", temporaryBlockId,
                            "block_id", "real_" + temporaryBlockId
                    ));
                }
            }
            return relations;
        }

        private List<Integer> buildColumnWidths(int columns) {
            List<Integer> widths = new ArrayList<>();
            for (int index = 0; index < columns; index++) {
                widths.add(100);
            }
            return widths;
        }

        private List<Map<String, Object>> buildMergeInfo(int rows, int columns) {
            List<Map<String, Object>> mergeInfo = new ArrayList<>();
            for (int index = 0; index < rows * columns; index++) {
                mergeInfo.add(Map.of(
                        "row_span", 1,
                        "col_span", 1
                ));
            }
            return mergeInfo;
        }

        private Integer asInteger(Object value) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        }

        private void writeJson(HttpExchange exchange, Map<String, Object> body) throws IOException {
            byte[] bytes = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

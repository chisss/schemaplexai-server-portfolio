package com.schemaplexai.service.integration.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 飞书文档投递服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuDocDeliveryService {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,9})\\s+(.*)$");
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^\\d+[.)]\\s+(.*)$");
    private static final Pattern TABLE_SEPARATOR_CELL_PATTERN = Pattern.compile("^:?-{3,}:?$");
    private static final int MAX_CHILDREN_PER_REQUEST = 50;
    private static final int RETRY_LIMIT = 5;
    private static final long RATE_LIMIT_BACKOFF_MILLIS = 500L;
    private static final long FOLDER_LOCK_BACKOFF_MILLIS = 2000L;
    private static final String DEFAULT_API_BASE_URL = "https://open.feishu.cn";
    private static final String DEFAULT_DOC_URL_PREFIX = "https://feishu.cn/docx/";
    private static final int FEISHU_TABLE_BLOCK_TYPE = 31;
    private static final int FEISHU_TABLE_CELL_BLOCK_TYPE = 32;
    private static final int MAX_FEISHU_TABLE_COLUMNS = 9;
    private static final int MIN_TABLE_COLUMN_WIDTH = 120;
    private static final int MAX_TABLE_COLUMN_WIDTH = 360;
    private static final int TARGET_TABLE_WIDTH = 860;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public FeishuDocDeliveryResult deliver(Map<String, Object> config, String title, String markdown) {
        if (!StringUtils.hasText(title)) {
            throw new IllegalArgumentException("飞书文档标题不能为空");
        }
        if (!StringUtils.hasText(markdown)) {
            throw new IllegalArgumentException("飞书文档内容不能为空");
        }

        try {
            String accessToken = requestTenantAccessToken(config);
            CreatedDocument createdDocument = createDocument(config, accessToken, title.trim());
            List<MarkdownElement> elements = parseMarkdown(markdown);
            if (elements.isEmpty()) {
                elements = List.of(new BlockElement(textBlock(markdown.trim())));
            }
            Integer revisionId = createdDocument.revisionId();
            List<MarkdownBlock> pendingBlocks = new ArrayList<>();
            for (MarkdownElement element : elements) {
                if (element instanceof BlockElement blockElement) {
                    pendingBlocks.add(blockElement.block());
                    if (pendingBlocks.size() >= MAX_CHILDREN_PER_REQUEST) {
                        revisionId = appendMarkdownBlocks(config, accessToken, createdDocument.documentId(), createdDocument.documentId(), pendingBlocks);
                        pendingBlocks.clear();
                    }
                    continue;
                }
                if (element instanceof TableElement tableElement) {
                    revisionId = appendMarkdownBlocks(config, accessToken, createdDocument.documentId(), createdDocument.documentId(), pendingBlocks);
                    pendingBlocks.clear();
                    revisionId = appendTable(config, accessToken, createdDocument.documentId(), tableElement.table());
                }
            }
            revisionId = appendMarkdownBlocks(config, accessToken, createdDocument.documentId(), createdDocument.documentId(), pendingBlocks);
            return new FeishuDocDeliveryResult(
                    createdDocument.documentId(),
                    buildDocumentUrl(config, createdDocument.documentId()),
                    createdDocument.title(),
                    revisionId,
                    elements.size()
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_SEND_FAILED, "飞书文档投递失败: " + ex.getMessage());
        }
    }

    private CreatedDocument createDocument(Map<String, Object> config, String accessToken, String title) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        String folderToken = readOptionalConfig(config, "document_folder_token", "documentFolderToken", "folder_token", "folderToken");
        if (StringUtils.hasText(folderToken)) {
            body.put("folder_token", folderToken);
        }
        Map<String, Object> responseBody = postJson(
                config,
                accessToken,
                apiBaseUrl(config) + "/open-apis/docx/v1/documents",
                body
        );
        Map<String, Object> data = asMap(responseBody.get("data"));
        Map<String, Object> document = asMap(data.get("document"));
        String documentId = asText(document.get("document_id"));
        if (!StringUtils.hasText(documentId)) {
            throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_SEND_FAILED, "飞书创建文档成功但未返回 document_id");
        }
        Integer revisionId = asInteger(document.get("revision_id"));
        String documentTitle = StringUtils.hasText(asText(document.get("title"))) ? asText(document.get("title")) : title;
        return new CreatedDocument(documentId, revisionId, documentTitle);
    }

    private Integer appendMarkdownBlocks(Map<String, Object> config,
                                         String accessToken,
                                         String documentId,
                                         String parentBlockId,
                                         List<MarkdownBlock> blocks) throws Exception {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        AppendBlocksResult result = appendBlocks(config, accessToken, documentId, parentBlockId, blocks);
        sleepQuietly(RATE_LIMIT_BACKOFF_MILLIS);
        return result.revisionId();
    }

    private AppendBlocksResult appendBlocks(Map<String, Object> config,
                                            String accessToken,
                                            String documentId,
                                            String parentBlockId,
                                            List<MarkdownBlock> blocks) throws Exception {
        if (blocks == null || blocks.isEmpty()) {
            return new AppendBlocksResult(null, List.of());
        }
        HttpUrl url = HttpUrl.parse(apiBaseUrl(config)
                + "/open-apis/docx/v1/documents/"
                + documentId
                + "/blocks/"
                + parentBlockId
                + "/children");
        if (url == null) {
            throw new IllegalArgumentException("飞书文档块接口地址非法");
        }
        HttpUrl resolvedUrl = url.newBuilder()
                .addQueryParameter("document_revision_id", "-1")
                .addQueryParameter("client_token", UUID.randomUUID().toString())
                .build();
        Map<String, Object> body = Map.of(
                "index", -1,
                "children", blocks.stream().map(this::toRequestBlock).toList()
        );
        Map<String, Object> responseBody = postJson(config, accessToken, resolvedUrl.toString(), body);
        Map<String, Object> data = asMap(responseBody.get("data"));
        return new AppendBlocksResult(
                asInteger(data.get("document_revision_id")),
                extractBlocks(data.get("children"))
        );
    }

    private Map<String, Object> toRequestBlock(MarkdownBlock block) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("block_type", block.blockType());
        if (block.attributes() != null && !block.attributes().isEmpty()) {
            payload.put(block.fieldName(), block.attributes());
            return payload;
        }
        if ("divider".equals(block.fieldName())) {
            payload.put("divider", Map.of());
            return payload;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("style", Map.of());
        content.put("elements", List.of(Map.of(
                "text_run", Map.of("content", block.content())
        )));
        if (block.language() != null) {
            content.put("language", block.language());
            content.put("wrap", Boolean.TRUE);
        }
        payload.put(block.fieldName(), content);
        return payload;
    }

    private Integer appendTable(Map<String, Object> config,
                                String accessToken,
                                String documentId,
                                MarkdownTable table) throws Exception {
        List<MarkdownTable> splitTables = splitWideTable(table);
        Integer revisionId = null;
        for (MarkdownTable splitTable : splitTables) {
            revisionId = appendSingleTable(config, accessToken, documentId, splitTable);
        }
        return revisionId;
    }

    private Integer appendSingleTable(Map<String, Object> config,
                                      String accessToken,
                                      String documentId,
                                      MarkdownTable table) throws Exception {
        HttpUrl url = HttpUrl.parse(apiBaseUrl(config)
                + "/open-apis/docx/v1/documents/"
                + documentId
                + "/blocks/"
                + documentId
                + "/descendant");
        if (url == null) {
            throw new IllegalArgumentException("飞书文档嵌套块接口地址非法");
        }
        HttpUrl resolvedUrl = url.newBuilder()
                .addQueryParameter("document_revision_id", "-1")
                .addQueryParameter("client_token", UUID.randomUUID().toString())
                .build();
        Map<String, Object> responseBody = postJson(
                config,
                accessToken,
                resolvedUrl.toString(),
                buildTableDescendantRequest(table)
        );
        Map<String, Object> data = asMap(responseBody.get("data"));
        Integer revisionId = asInteger(data.get("document_revision_id"));
        sleepQuietly(RATE_LIMIT_BACKOFF_MILLIS);
        return revisionId;
    }

    private Map<String, Object> buildTableDescendantRequest(MarkdownTable table) {
        String tableBlockId = "table_" + UUID.randomUUID().toString().replace("-", "");
        List<String> topLevelChildren = List.of(tableBlockId);
        List<String> cellBlockIds = new ArrayList<>();
        List<Map<String, Object>> descendants = new ArrayList<>();
        for (int index = 0; index < table.totalCellCount(); index++) {
            cellBlockIds.add("cell_" + (index + 1));
        }
        descendants.add(tableDescendantBlock(tableBlockId, table, cellBlockIds));
        for (int index = 0; index < table.totalCellCount(); index++) {
            String cellBlockId = cellBlockIds.get(index);
            String textBlockId = cellBlockId + "_text";
            descendants.add(tableCellDescendantBlock(cellBlockId, textBlockId));
            descendants.add(textDescendantBlock(textBlockId, table.cellContent(index)));
        }
        return Map.of(
                "index", -1,
                "children_id", topLevelChildren,
                "descendants", descendants
        );
    }

    private Map<String, Object> tableDescendantBlock(String tableBlockId,
                                                     MarkdownTable table,
                                                     List<String> cellBlockIds) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("row_size", table.rowCount());
        property.put("column_size", table.columnCount());
        property.put("header_row", Boolean.TRUE);
        property.put("column_width", buildColumnWidths(table));
        return Map.of(
                "block_id", tableBlockId,
                "block_type", FEISHU_TABLE_BLOCK_TYPE,
                "table", Map.of("property", property),
                "children", cellBlockIds
        );
    }

    private List<Integer> buildColumnWidths(MarkdownTable table) {
        if (table == null || table.columnCount() <= 0) {
            return List.of();
        }
        List<Integer> rawWidths = new ArrayList<>();
        int totalWidth = 0;
        for (int columnIndex = 0; columnIndex < table.columnCount(); columnIndex++) {
            int width = estimateColumnWidth(table, columnIndex);
            rawWidths.add(width);
            totalWidth += width;
        }
        if (totalWidth <= 0 || totalWidth == TARGET_TABLE_WIDTH) {
            return rawWidths;
        }
        List<Integer> scaledWidths = new ArrayList<>();
        int adjustedTotal = 0;
        for (int index = 0; index < rawWidths.size(); index++) {
            int width = rawWidths.get(index);
            int scaledWidth = Math.max(MIN_TABLE_COLUMN_WIDTH,
                    Math.min(MAX_TABLE_COLUMN_WIDTH, Math.round(width * (TARGET_TABLE_WIDTH / (float) totalWidth))));
            scaledWidths.add(scaledWidth);
            adjustedTotal += scaledWidth;
        }
        if (adjustedTotal == TARGET_TABLE_WIDTH || scaledWidths.isEmpty()) {
            return scaledWidths;
        }
        int remainder = TARGET_TABLE_WIDTH - adjustedTotal;
        int cursor = 0;
        while (remainder != 0 && cursor < scaledWidths.size() * 4) {
            int index = cursor % scaledWidths.size();
            int current = scaledWidths.get(index);
            if (remainder > 0 && current < MAX_TABLE_COLUMN_WIDTH) {
                scaledWidths.set(index, current + 1);
                remainder--;
            } else if (remainder < 0 && current > MIN_TABLE_COLUMN_WIDTH) {
                scaledWidths.set(index, current - 1);
                remainder++;
            }
            cursor++;
        }
        return scaledWidths;
    }

    private int estimateColumnWidth(MarkdownTable table, int columnIndex) {
        int maxWeight = 0;
        for (List<String> row : table.rows()) {
            if (row == null || columnIndex >= row.size()) {
                continue;
            }
            maxWeight = Math.max(maxWeight, textVisualWeight(row.get(columnIndex)));
        }
        if (table.columnCount() == 2 && columnIndex == 0) {
            return Math.max(MIN_TABLE_COLUMN_WIDTH, Math.min(220, 60 + maxWeight * 10));
        }
        return Math.max(MIN_TABLE_COLUMN_WIDTH, Math.min(MAX_TABLE_COLUMN_WIDTH, 80 + maxWeight * 10));
    }

    private int textVisualWeight(String content) {
        if (!StringUtils.hasText(content)) {
            return 4;
        }
        int weight = 0;
        for (char character : content.toCharArray()) {
            if (Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN) {
                weight += 2;
            } else if (Character.isWhitespace(character)) {
                weight += 1;
            } else {
                weight += 1;
            }
        }
        return Math.max(4, Math.min(28, weight));
    }

    private Map<String, Object> tableCellDescendantBlock(String cellBlockId, String textBlockId) {
        return Map.of(
                "block_id", cellBlockId,
                "block_type", FEISHU_TABLE_CELL_BLOCK_TYPE,
                "table_cell", Map.of(),
                "children", List.of(textBlockId)
        );
    }

    private Map<String, Object> textDescendantBlock(String textBlockId, String content) {
        return Map.of(
                "block_id", textBlockId,
                "block_type", 2,
                "text", Map.of(
                        "elements", List.of(Map.of(
                                "text_run", Map.of("content", content != null ? content : "")
                        ))
                ),
                "children", List.of()
        );
    }

    private List<MarkdownTable> splitWideTable(MarkdownTable table) {
        if (table == null || table.columnCount() <= MAX_FEISHU_TABLE_COLUMNS) {
            return List.of(table);
        }
        List<MarkdownTable> splitTables = new ArrayList<>();
        int repeatedLeadingColumns = resolveRepeatedLeadingColumns(table);
        int firstChunkEnd = Math.min(MAX_FEISHU_TABLE_COLUMNS, table.columnCount());
        splitTables.add(table.selectColumns(columnIndexes(0, firstChunkEnd)));
        int payloadColumnsPerChunk = Math.max(1, MAX_FEISHU_TABLE_COLUMNS - repeatedLeadingColumns);
        for (int nextColumn = firstChunkEnd; nextColumn < table.columnCount(); nextColumn += payloadColumnsPerChunk) {
            List<Integer> indexes = new ArrayList<>();
            indexes.addAll(columnIndexes(0, repeatedLeadingColumns));
            indexes.addAll(columnIndexes(nextColumn, Math.min(table.columnCount(), nextColumn + payloadColumnsPerChunk)));
            splitTables.add(table.selectColumns(indexes));
        }
        return splitTables;
    }

    private int resolveRepeatedLeadingColumns(MarkdownTable table) {
        if (table == null || table.columnCount() <= 1) {
            return 1;
        }
        String firstHeader = table.headerCell(0);
        if (!StringUtils.hasText(firstHeader)) {
            return 1;
        }
        String normalized = firstHeader.trim().toLowerCase(Locale.ROOT);
        if ("#".equals(normalized) || "no".equals(normalized) || "no.".equals(normalized) || "序号".equals(normalized)) {
            return Math.min(2, table.columnCount());
        }
        return 1;
    }

    private List<Integer> columnIndexes(int startInclusive, int endExclusive) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = startInclusive; index < endExclusive; index++) {
            indexes.add(index);
        }
        return indexes;
    }

    private String requestTenantAccessToken(Map<String, Object> config) throws Exception {
        String appId = readRequiredConfig(config, "app_id", "appId", "APP_ID");
        String appSecret = readRequiredConfig(config, "app_secret", "appSecret", "App Secret");
        Map<String, Object> tokenRequest = Map.of(
                "app_id", appId,
                "app_secret", appSecret
        );
        Map<String, Object> responseBody = postJson(
                config,
                null,
                apiBaseUrl(config) + "/open-apis/auth/v3/tenant_access_token/internal",
                tokenRequest
        );
        String accessToken = asText(responseBody.get("tenant_access_token"));
        if (!StringUtils.hasText(accessToken)) {
            throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_SEND_FAILED, "获取飞书 tenant_access_token 失败: 响应缺少 token");
        }
        return accessToken;
    }

    private Map<String, Object> postJson(Map<String, Object> config,
                                         String accessToken,
                                         String url,
                                         Map<String, Object> body) throws Exception {
        String requestBody = objectMapper.writeValueAsString(body);
        Exception lastException = null;
        for (int attempt = 1; attempt <= RETRY_LIMIT; attempt++) {
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(requestBody, JSON_TYPE));
            if (StringUtils.hasText(accessToken)) {
                builder.addHeader("Authorization", "Bearer " + accessToken);
            }
            try (Response response = httpClient.newCall(builder.build()).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                Map<String, Object> responseBody = parseResponse(respBody);
                Object code = responseBody.get("code");
                boolean retryable = isRetryable(response.code(), code);
                long backoff = isFolderLocked(code) ? FOLDER_LOCK_BACKOFF_MILLIS : RATE_LIMIT_BACKOFF_MILLIS;
                if (!response.isSuccessful()) {
                    if (retryable && attempt < RETRY_LIMIT) {
                        sleepQuietly(backoff * attempt);
                        continue;
                    }
                    throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_SEND_FAILED,
                            "飞书接口调用失败: http=" + response.code() + ", body=" + respBody);
                }
                if (code != null && !"0".equals(String.valueOf(code))) {
                    if (retryable && attempt < RETRY_LIMIT) {
                        sleepQuietly(backoff * attempt);
                        continue;
                    }
                    throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_SEND_FAILED,
                            "飞书接口调用失败: code=" + code + ", msg=" + responseBody.get("msg"));
                }
                return responseBody;
            } catch (IOException ex) {
                lastException = ex;
                if (attempt < RETRY_LIMIT) {
                    sleepQuietly(RATE_LIMIT_BACKOFF_MILLIS * attempt);
                    continue;
                }
                throw ex;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_SEND_FAILED, "飞书接口调用失败");
    }

    private Map<String, Object> parseResponse(String respBody) throws IOException {
        if (!StringUtils.hasText(respBody)) {
            return Map.of();
        }
        return objectMapper.readValue(respBody, Map.class);
    }

    private boolean isRetryable(int httpCode, Object code) {
        return httpCode == 429 || "99991400".equals(String.valueOf(code)) || isFolderLocked(code);
    }

    private boolean isFolderLocked(Object code) {
        return "1770036".equals(String.valueOf(code));
    }

    private List<Map<String, Object>> extractBlocks(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> block = asMap(item);
            if (!block.isEmpty()) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    private List<MarkdownElement> parseMarkdown(String markdown) {
        List<MarkdownElement> elements = new ArrayList<>();
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder paragraph = new StringBuilder();
        StringBuilder codeBlock = new StringBuilder();
        String codeLanguage = null;
        boolean inCodeBlock = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String trimmed = line.trim();
            if (inCodeBlock) {
                if (trimmed.startsWith("```")) {
                    elements.add(new BlockElement(codeBlock(codeBlock.toString().stripTrailing(), codeLanguage)));
                    codeBlock.setLength(0);
                    codeLanguage = null;
                    inCodeBlock = false;
                } else {
                    if (codeBlock.length() > 0) {
                        codeBlock.append('\n');
                    }
                    codeBlock.append(line);
                }
                continue;
            }

            if (trimmed.startsWith("```")) {
                flushParagraph(elements, paragraph);
                inCodeBlock = true;
                codeLanguage = trimmed.length() > 3 ? trimmed.substring(3).trim() : null;
                continue;
            }
            TableParseResult tableParseResult = tryParseTable(lines, index);
            if (tableParseResult != null) {
                flushParagraph(elements, paragraph);
                elements.add(new TableElement(tableParseResult.table()));
                index += tableParseResult.consumedLines() - 1;
                continue;
            }
            if (!StringUtils.hasText(trimmed)) {
                flushParagraph(elements, paragraph);
                continue;
            }
            if (isDivider(trimmed)) {
                flushParagraph(elements, paragraph);
                elements.add(new BlockElement(new MarkdownBlock(22, "divider", null, null, null)));
                continue;
            }

            Matcher headingMatcher = HEADING_PATTERN.matcher(line);
            if (headingMatcher.matches()) {
                flushParagraph(elements, paragraph);
                int level = Math.min(headingMatcher.group(1).length(), 9);
                elements.add(new BlockElement(headingBlock(level, headingMatcher.group(2).trim())));
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flushParagraph(elements, paragraph);
                elements.add(new BlockElement(listBlock(12, "bullet", trimmed.substring(2).trim())));
                continue;
            }
            Matcher orderedMatcher = ORDERED_LIST_PATTERN.matcher(trimmed);
            if (orderedMatcher.matches()) {
                flushParagraph(elements, paragraph);
                elements.add(new BlockElement(listBlock(13, "ordered", orderedMatcher.group(1).trim())));
                continue;
            }
            if (trimmed.startsWith("> ")) {
                flushParagraph(elements, paragraph);
                elements.add(new BlockElement(new MarkdownBlock(15, "quote", trimmed.substring(2).trim(), null, null)));
                continue;
            }

            if (paragraph.length() > 0) {
                paragraph.append('\n');
            }
            paragraph.append(line.stripTrailing());
        }
        if (inCodeBlock && codeBlock.length() > 0) {
            elements.add(new BlockElement(codeBlock(codeBlock.toString().stripTrailing(), codeLanguage)));
        }
        flushParagraph(elements, paragraph);
        return elements;
    }

    private TableParseResult tryParseTable(String[] lines, int startIndex) {
        if (startIndex + 1 >= lines.length) {
            return null;
        }
        String headerLine = lines[startIndex];
        String separatorLine = lines[startIndex + 1];
        if (!containsTableDelimiter(headerLine)) {
            return null;
        }
        List<String> headerCells = splitTableRow(headerLine);
        if (headerCells.size() < 2 || !isTableSeparator(separatorLine, headerCells.size())) {
            return null;
        }
        List<List<String>> rows = new ArrayList<>();
        rows.add(normalizeTableRow(headerCells, headerCells.size()));
        int cursor = startIndex + 2;
        while (cursor < lines.length) {
            String candidate = lines[cursor];
            String trimmed = candidate.trim();
            if (!StringUtils.hasText(trimmed) || !containsTableDelimiter(candidate) || isTableSeparator(candidate, headerCells.size())) {
                break;
            }
            rows.add(normalizeTableRow(splitTableRow(candidate), headerCells.size()));
            cursor++;
        }
        return new TableParseResult(new MarkdownTable(rows), cursor - startIndex);
    }

    private boolean containsTableDelimiter(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }
        boolean escaped = false;
        int pipeCount = 0;
        for (char current : line.toCharArray()) {
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '|') {
                pipeCount++;
            }
        }
        return pipeCount > 0;
    }

    private boolean isTableSeparator(String line, int expectedColumns) {
        List<String> cells = splitTableRow(line);
        if (cells.size() != expectedColumns) {
            return false;
        }
        return cells.stream().allMatch(cell -> TABLE_SEPARATOR_CELL_PATTERN.matcher(cell.replace(" ", "")).matches());
    }

    private List<String> splitTableRow(String line) {
        if (!StringUtils.hasText(line)) {
            return List.of();
        }
        String normalized = line.trim();
        if (normalized.startsWith("|")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("|")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            if (character == '|') {
                cells.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(character);
        }
        cells.add(current.toString().trim());
        return cells;
    }

    private List<String> normalizeTableRow(List<String> cells, int expectedColumns) {
        List<String> normalized = new ArrayList<>(cells);
        if (normalized.size() > expectedColumns) {
            List<String> head = new ArrayList<>(normalized.subList(0, expectedColumns - 1));
            head.add(String.join(" | ", normalized.subList(expectedColumns - 1, normalized.size())));
            normalized = head;
        }
        while (normalized.size() < expectedColumns) {
            normalized.add("");
        }
        return normalized;
    }

    private void flushParagraph(List<MarkdownElement> elements, StringBuilder paragraph) {
        if (paragraph.length() == 0) {
            return;
        }
        elements.add(new BlockElement(textBlock(paragraph.toString().trim())));
        paragraph.setLength(0);
    }

    private MarkdownBlock textBlock(String content) {
        return new MarkdownBlock(2, "text", content, null, null);
    }

    private MarkdownBlock listBlock(int blockType, String fieldName, String content) {
        return new MarkdownBlock(blockType, fieldName, content, null, null);
    }

    private MarkdownBlock headingBlock(int level, String content) {
        return new MarkdownBlock(level + 2, "heading" + level, content, null, null);
    }

    private MarkdownBlock codeBlock(String content, String language) {
        return new MarkdownBlock(14, "code", content, resolveCodeLanguage(language), null);
    }

    private Integer resolveCodeLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return 1;
        }
        return switch (language.trim().toLowerCase(Locale.ROOT)) {
            case "java" -> 29;
            case "javascript", "js" -> 30;
            case "typescript", "ts" -> 63;
            case "python", "py" -> 49;
            case "sql" -> 56;
            case "json" -> 28;
            case "markdown", "md" -> 39;
            case "bash" -> 7;
            case "shell", "sh" -> 60;
            case "yaml", "yml" -> 67;
            case "xml" -> 66;
            case "http" -> 26;
            default -> 1;
        };
    }

    private boolean isDivider(String trimmed) {
        return "---".equals(trimmed) || "***".equals(trimmed);
    }

    private List<List<MarkdownBlock>> split(List<MarkdownBlock> blocks, int chunkSize) {
        List<List<MarkdownBlock>> chunks = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) {
            return chunks;
        }
        for (int start = 0; start < blocks.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, blocks.size());
            chunks.add(blocks.subList(start, end));
        }
        return chunks;
    }

    private String buildDocumentUrl(Map<String, Object> config, String documentId) {
        String configuredPrefix = readOptionalConfig(config,
                "document_url_prefix",
                "documentUrlPrefix",
                "document_base_url",
                "documentBaseUrl",
                "doc_base_url",
                "docBaseUrl");
        if (!StringUtils.hasText(configuredPrefix)) {
            return DEFAULT_DOC_URL_PREFIX + documentId;
        }
        String prefix = configuredPrefix.trim();
        if (prefix.contains("{documentId}")) {
            return prefix.replace("{documentId}", documentId);
        }
        if (prefix.contains("%s")) {
            return prefix.formatted(documentId);
        }
        if (prefix.endsWith("/")) {
            return prefix + documentId;
        }
        return prefix + "/" + documentId;
    }

    private String apiBaseUrl(Map<String, Object> config) {
        String configured = readOptionalConfig(config, "base_url", "baseUrl");
        return StringUtils.hasText(configured) ? configured.trim() : DEFAULT_API_BASE_URL;
    }

    private String readRequiredConfig(Map<String, Object> config, String... keys) {
        String value = readOptionalConfig(config, keys);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("飞书配置缺少字段: " + String.join("/", keys));
        }
        return value;
    }

    private String readOptionalConfig(Map<String, Object> config, String... keys) {
        if (config == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = config.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private String asText(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("飞书文档投递等待被中断");
        }
    }

    private record CreatedDocument(String documentId, Integer revisionId, String title) {
    }

    private sealed interface MarkdownElement permits BlockElement, TableElement {
    }

    private record BlockElement(MarkdownBlock block) implements MarkdownElement {
    }

    private record TableElement(MarkdownTable table) implements MarkdownElement {
    }

    private record TableParseResult(MarkdownTable table, int consumedLines) {
    }

    private record MarkdownTable(List<List<String>> rows) {

        private int rowCount() {
            return rows != null ? rows.size() : 0;
        }

        private int columnCount() {
            if (rows == null || rows.isEmpty() || rows.getFirst() == null) {
                return 0;
            }
            return rows.getFirst().size();
        }

        private int totalCellCount() {
            return rowCount() * columnCount();
        }

        private String headerCell(int index) {
            if (rows == null || rows.isEmpty() || rows.getFirst() == null || index < 0 || index >= rows.getFirst().size()) {
                return "";
            }
            return rows.getFirst().get(index);
        }

        private String cellContent(int index) {
            if (index < 0 || columnCount() == 0) {
                return "";
            }
            int rowIndex = index / columnCount();
            int columnIndex = index % columnCount();
            if (rows == null || rowIndex >= rows.size() || rows.get(rowIndex) == null || columnIndex >= rows.get(rowIndex).size()) {
                return "";
            }
            return rows.get(rowIndex).get(columnIndex);
        }

        private MarkdownTable selectColumns(List<Integer> indexes) {
            if (rows == null || rows.isEmpty() || indexes == null || indexes.isEmpty()) {
                return new MarkdownTable(List.of());
            }
            List<List<String>> selectedRows = new ArrayList<>();
            for (List<String> row : rows) {
                List<String> selected = new ArrayList<>();
                for (Integer index : indexes) {
                    if (index == null || index < 0 || row == null || index >= row.size()) {
                        selected.add("");
                        continue;
                    }
                    selected.add(row.get(index));
                }
                selectedRows.add(selected);
            }
            return new MarkdownTable(selectedRows);
        }
    }

    private record AppendBlocksResult(Integer revisionId, List<Map<String, Object>> children) {
    }

    private record MarkdownBlock(int blockType,
                                 String fieldName,
                                 String content,
                                 Integer language,
                                 Map<String, Object> attributes) {
    }

    public record FeishuDocDeliveryResult(String documentId,
                                          String documentUrl,
                                          String title,
                                          Integer revisionId,
                                          int blockCount) {
    }
}

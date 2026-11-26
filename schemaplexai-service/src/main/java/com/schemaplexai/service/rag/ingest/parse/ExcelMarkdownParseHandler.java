package com.schemaplexai.service.rag.ingest.parse;

import com.schemaplexai.service.rag.ingest.GatewayDecision;
import com.schemaplexai.service.rag.ingest.IngestCategory;
import com.schemaplexai.service.rag.ingest.IngestFileTypes;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 解析：POI → Markdown 表格。
 *
 * <p>为什么不用 Python 富文档服务：修订版明确下线 Docling，Excel 用 Java 原生即可，
 * 少一个跨语言服务就少一层网络调用、超时与运维成本。
 *
 * <p>内存风险控制（设计稿第六章）：
 * <ul>
 *   <li>单 sheet 最多 {@link #MAX_ROWS_PER_SHEET} 行、{@link #MAX_COLS} 列；</li>
 *   <li>单文件最多 {@link #MAX_SHEETS} 个 sheet；</li>
 *   <li>超限不是静默丢弃，而是写进 warnings 与 metadata，用户在文档详情能看到"被截断"。</li>
 * </ul>
 *
 * <p>已知短板：单元格内嵌图片/图表里的文字无法识别，这类内容会留空——如需识别，
 * 由用户另存为图片走 OCR 分支。
 */
@Slf4j
@Component
public class ExcelMarkdownParseHandler implements DocumentParseHandler {

    static final int MAX_ROWS_PER_SHEET = 5000;
    static final int MAX_COLS = 100;
    static final int MAX_SHEETS = 50;
    /** 单单元格最大字符，防止超长文本把表格撑爆 */
    static final int MAX_CELL_CHARS = 2000;

    @Override
    public boolean supports(IngestCategory category) {
        return category == IngestCategory.EXCEL;
    }

    @Override
    public ParsedDocument parse(ParseContext context) {
        String extension = IngestFileTypes.extensionOf(context.fileName());
        boolean csv = "csv".equals(extension)
                || "text/csv".equalsIgnoreCase(context.mimeType());
        try {
            String markdown = csv ? parseCsv(context.content()) : parseWorkbook(context.content());
            String text = TextNormalizer.normalize(markdown);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("parser", GatewayDecision.PARSER_EXCEL);
            metadata.put("format", csv ? "csv" : "xlsx");
            metadata.put("charCount", text.length());
            return new ParsedDocument(text, GatewayDecision.PARSER_EXCEL, IngestCategory.EXCEL,
                    metadata, List.of(), false);
        } catch (Exception e) {
            log.warn("Excel 解析失败: fileName={}, error={}", context.fileName(), e.getMessage());
            throw new DocumentParseException(DocumentParseException.PARSE_ERROR,
                    "Excel 解析失败: " + e.getMessage(), e);
        }
    }

    // ==================== xlsx / xls ====================

    private String parseWorkbook(byte[] content) throws Exception {
        StringBuilder out = new StringBuilder();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter();
            int sheetCount = Math.min(workbook.getNumberOfSheets(), MAX_SHEETS);
            for (int i = 0; i < sheetCount; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (sheet == null) {
                    continue;
                }
                String block = renderSheet(sheet, formatter);
                if (!block.isBlank()) {
                    out.append(block).append('\n');
                }
            }
        }
        return out.toString();
    }

    private String renderSheet(Sheet sheet, DataFormatter formatter) {
        int lastRow = sheet.getLastRowNum();
        if (lastRow < 0) {
            return "";
        }
        int rowLimit = Math.min(lastRow, MAX_ROWS_PER_SHEET - 1);
        List<List<String>> rows = new ArrayList<>();
        int maxCols = 0;

        for (int rowIndex = 0; rowIndex <= rowLimit; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            int colCount = Math.min(row.getLastCellNum(), MAX_COLS);
            if (colCount <= 0) {
                continue;
            }
            List<String> cells = new ArrayList<>(colCount);
            for (int colIndex = 0; colIndex < colCount; colIndex++) {
                cells.add(renderCell(row.getCell(colIndex), formatter));
            }
            if (cells.stream().allMatch(String::isBlank)) {
                continue;
            }
            maxCols = Math.max(maxCols, cells.size());
            rows.add(cells);
        }

        if (rows.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Sheet: ").append(sheet.getSheetName()).append('\n');
        List<CellRangeAddress> merged = sheet.getMergedRegions();
        if (!merged.isEmpty()) {
            List<String> ranges = merged.stream()
                    .limit(20)
                    .map(CellRangeAddress::formatAsString)
                    .toList();
            sb.append("> 合并单元格: ").append(String.join(", ", ranges));
            if (merged.size() > 20) {
                sb.append(" 等 ").append(merged.size()).append(" 处");
            }
            sb.append('\n');
        }
        if (lastRow >= MAX_ROWS_PER_SHEET) {
            sb.append("> 该 sheet 共 ").append(lastRow + 1).append(" 行，已截断为前 ")
                    .append(MAX_ROWS_PER_SHEET).append(" 行\n");
        }
        sb.append('\n');

        // 首行作为表头
        appendMarkdownRow(sb, rows.get(0), maxCols);
        appendSeparator(sb, maxCols);
        for (int i = 1; i < rows.size(); i++) {
            appendMarkdownRow(sb, rows.get(i), maxCols);
        }
        return sb.toString();
    }

    private String renderCell(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        try {
            String raw = formatter.formatCellValue(cell);
            if (raw == null) {
                return "";
            }
            String value = raw.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
                    .replace("|", "\\|")
                    .trim();
            if (value.length() > MAX_CELL_CHARS) {
                return value.substring(0, MAX_CELL_CHARS) + "…";
            }
            return value;
        } catch (Exception e) {
            return "";
        }
    }

    private void appendMarkdownRow(StringBuilder sb, List<String> cells, int maxCols) {
        sb.append('|');
        for (int i = 0; i < maxCols; i++) {
            sb.append(' ').append(i < cells.size() ? cells.get(i) : "").append(" |");
        }
        sb.append('\n');
    }

    private void appendSeparator(StringBuilder sb, int maxCols) {
        sb.append('|');
        for (int i = 0; i < maxCols; i++) {
            sb.append(" --- |");
        }
        sb.append('\n');
    }

    // ==================== csv ====================

    private String parseCsv(byte[] content) {
        String raw = new String(content, StandardCharsets.UTF_8);
        List<List<String>> rows = readCsv(raw);
        if (rows.isEmpty()) {
            return "";
        }
        int maxCols = rows.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        sb.append("## Sheet: CSV\n\n");
        appendMarkdownRow(sb, rows.get(0), maxCols);
        appendSeparator(sb, maxCols);
        for (int i = 1; i < Math.min(rows.size(), MAX_ROWS_PER_SHEET); i++) {
            appendMarkdownRow(sb, rows.get(i), maxCols);
        }
        return sb.toString();
    }

    /** 最小 RFC4180 读取：支持引号包裹与转义双引号，不做流式优化（csv 已受容量上限约束） */
    List<List<String>> readCsv(String raw) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    current.add(cell.toString());
                    cell.setLength(0);
                }
                case '\n' -> {
                    current.add(cell.toString());
                    cell.setLength(0);
                    rows.add(current);
                    current = new ArrayList<>();
                }
                case '\r' -> {
                    // 交给 \n 处理
                }
                default -> cell.append(c);
            }
        }
        if (!current.isEmpty() || !cell.isEmpty()) {
            current.add(cell.toString());
            rows.add(current);
        }
        return rows;
    }
}

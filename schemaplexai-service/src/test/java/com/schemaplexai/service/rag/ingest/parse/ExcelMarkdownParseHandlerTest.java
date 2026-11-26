package com.schemaplexai.service.rag.ingest.parse;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.ingest.IngestCategory;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Excel → Markdown：表头、多 sheet、合并单元格提示、CSV 引号处理。
 */
class ExcelMarkdownParseHandlerTest {

    private final ExcelMarkdownParseHandler handler = new ExcelMarkdownParseHandler();

    @Test
    @DisplayName("多 sheet 转 Markdown 表格，保留表头与 sheet 名")
    void shouldRenderEachSheetAsMarkdownTable() throws Exception {
        byte[] xlsx = buildWorkbook();

        ParsedDocument parsed = handler.parse(context("额度表.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx));

        assertThat(parsed.hasText()).isTrue();
        assertThat(parsed.text()).contains("## Sheet: 额度");
        assertThat(parsed.text()).contains("| 产品 | 额度 |");
        assertThat(parsed.text()).contains("| 现金贷 | 50000 |");
        assertThat(parsed.text()).contains("## Sheet: 利率");
        assertThat(parsed.parserId()).isEqualTo("excel-poi");
    }

    @Test
    @DisplayName("合并单元格区域写入提示，避免表格断片后丢失结构信息")
    void shouldNoteMergedRegions() throws Exception {
        byte[] xlsx = buildWorkbook();

        ParsedDocument parsed = handler.parse(context("额度表.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx));

        assertThat(parsed.text()).contains("合并单元格");
    }

    @Test
    @DisplayName("单元格里的竖线与换行不破坏表格结构")
    void shouldEscapePipeAndNewlineInCells() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("说明");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("字段");
            header.createCell(1).setCellValue("规则");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("a|b");
            data.createCell(1).setCellValue("第一行\n第二行");
            workbook.write(out);
            xlsx = out.toByteArray();
        }

        ParsedDocument parsed = handler.parse(context("说明.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx));

        assertThat(parsed.text()).contains("a\\|b");
        assertThat(parsed.text()).doesNotContain("\n第二行");
    }

    @Test
    @DisplayName("CSV 走同一解析器，支持引号包裹的逗号")
    void shouldParseCsvWithQuotedComma() {
        String csv = "产品,说明\n现金贷,\"额度 5,000 元起\"\n";
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);

        ParsedDocument parsed = handler.parse(context("额度.csv", "text/csv", bytes));

        assertThat(parsed.text()).contains("| 产品 | 说明 |");
        assertThat(parsed.text()).contains("额度 5,000 元起");
        assertThat(parsed.metadata()).containsEntry("format", "csv");
    }

    @Test
    @DisplayName("空 sheet 不产出空表格块")
    void shouldSkipEmptySheet() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("空表");
            Sheet sheet = workbook.createSheet("有数据");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("only");
            workbook.write(out);
            xlsx = out.toByteArray();
        }

        ParsedDocument parsed = handler.parse(context("t.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx));

        assertThat(parsed.text()).doesNotContain("## Sheet: 空表");
        assertThat(parsed.text()).contains("## Sheet: 有数据");
    }

    private byte[] buildWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("额度");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("产品");
            header.createCell(1).setCellValue("额度");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("现金贷");
            data.createCell(1).setCellValue(50000);

            Sheet second = workbook.createSheet("利率");
            Row secondHeader = second.createRow(0);
            secondHeader.createCell(0).setCellValue("产品");
            secondHeader.createCell(1).setCellValue("年化");
            Row secondData = second.createRow(1);
            secondData.createCell(0).setCellValue("现金贷");
            secondData.createCell(1).setCellValue("3.6%");

            // 合并单元格放在独立 sheet，避免影响表头断言
            Sheet note = workbook.createSheet("备注");
            Row noteRow = note.createRow(0);
            noteRow.createCell(0).setCellValue("本表由风控提供");
            note.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private ParseContext context(String fileName, String mime, byte[] content) {
        return new ParseContext(fileName, mime, IngestCategory.EXCEL, content, settings());
    }

    private RagRuntimeSettings settings() {
        return RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .ocrMinTextChars(20)
                .build();
    }
}

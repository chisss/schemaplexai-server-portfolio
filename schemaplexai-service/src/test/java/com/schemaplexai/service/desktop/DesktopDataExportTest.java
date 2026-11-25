package com.schemaplexai.service.desktop;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopDataExportTest {

    private final DesktopDataExport exports = new DesktopDataExport();

    @Test
    void csvEscapesFormulaValuesAndMarksTruncatedSnapshots() {
        byte[] content = exports.encode("csv", List.of("value"),
                List.of(Map.of("value", "=SUM(A1:A2)")), true);

        String csv = new String(content, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("'=SUM(A1:A2)"));
        assertTrue(csv.contains("结果已截断"));
    }

    @Test
    void excelPreservesLongIdentifiersAsText() throws Exception {
        byte[] content = exports.encode("xlsx", List.of("id"),
                List.of(Map.of("id", "1234567890123456789")), false);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var cell = workbook.getSheetAt(0).getRow(1).getCell(0);
            assertEquals(CellType.STRING, cell.getCellType());
            assertEquals("1234567890123456789", cell.getStringCellValue());
        }
    }
}

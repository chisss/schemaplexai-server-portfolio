package com.schemaplexai.service.desktop;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

final class DesktopDataExport {
    byte[] encode(String format, List<String> keys, List<Map<String, Object>> rows, boolean truncated) {
        return "csv".equals(format) ? csv(keys, rows, truncated) : workbook(keys, rows, truncated);
    }

    private byte[] csv(List<String> keys, List<Map<String, Object>> rows, boolean truncated) {
        StringBuilder csv = new StringBuilder("\ufeff");
        writeCsv(csv, keys);
        for (var row : rows) writeCsv(csv, keys.stream().map(row::get).toList());
        if (truncated) writeCsv(csv, List.of("结果已截断，仅包含当前查询快照"));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void writeCsv(StringBuilder csv, List<?> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) csv.append(',');
            Object value = values.get(index);
            String cell = value == null ? "" : String.valueOf(value);
            String normal = cell.stripLeading();
            if (value instanceof String && (dangerousPrefix(normal) || cell.startsWith("\t")
                    || cell.startsWith("\r") || cell.startsWith("\n"))) cell = "'" + cell;
            csv.append('"').append(cell.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    private boolean dangerousPrefix(String text) {
        return !text.isEmpty() && "=+-@＝＋－＠".indexOf(text.charAt(0)) >= 0;
    }

    private byte[] workbook(List<String> keys, List<Map<String, Object>> rows, boolean truncated) {
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("查询结果");
            var dates = workbook.createCellStyle();
            dates.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            var header = sheet.createRow(0);
            for (int column = 0; column < keys.size(); column++) header.createCell(column).setCellValue(keys.get(column));
            int index = 1;
            for (var values : rows) {
                var row = sheet.createRow(index++);
                for (int column = 0; column < keys.size(); column++) {
                    var cell = row.createCell(column);
                    Object value = values.get(keys.get(column));
                    switch (value) {
                        case null -> { }
                        case Boolean bool -> cell.setCellValue(bool);
                        case Number number when safeNumeric(number) -> cell.setCellValue(number.doubleValue());
                        case LocalDate day -> {
                            cell.setCellStyle(dates);
                            cell.setCellValue(day);
                        }
                        case LocalDateTime time -> {
                            cell.setCellStyle(dates);
                            cell.setCellValue(time);
                        }
                        default -> {
                            String text = value.toString();
                            cell.setCellValue(text.substring(0, Math.min(32767, text.length())));
                        }
                    }
                }
            }
            var info = workbook.createSheet("导出说明");
            info.createRow(0).createCell(0).setCellValue(truncated ? "结果已截断，仅包含当前查询快照" : "当前查询快照");
            info.createRow(1).createCell(0).setCellValue("已导出 " + rows.size() + " 行");
            workbook.write(bytes);
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("无法生成 Excel 导出", ex);
        }
    }

    private boolean safeNumeric(Number number) {
        if (!Double.isFinite(number.doubleValue())) return false;
        return new BigDecimal(number.toString()).stripTrailingZeros().precision() <= 15;
    }
}

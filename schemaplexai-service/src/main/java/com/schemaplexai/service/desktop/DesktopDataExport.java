package com.schemaplexai.service.desktop;

import com.schemaplexai.model.vo.semantic.SemanticQueryValidationVO;
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
        return encode(format, keys, rows, truncated, null);
    }

    byte[] encode(String format, List<String> keys, List<Map<String, Object>> rows, boolean truncated,
                  SemanticQueryValidationVO validation) {
        return "csv".equals(format) ? csv(keys, rows, truncated)
                : workbook(keys, rows, truncated, validationSummary(validation));
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

    private byte[] workbook(List<String> keys, List<Map<String, Object>> rows, boolean truncated, List<String> summary) {
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
            for (int indexInfo = 0; indexInfo < summary.size(); indexInfo++) {
                info.createRow(indexInfo + 2).createCell(0).setCellValue(summary.get(indexInfo));
            }
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

    private List<String> validationSummary(SemanticQueryValidationVO validation) {
        if (validation == null) {
            return List.of();
        }
        String technical = "PASSED".equals(validation.technicalStatus()) ? "技术校验已通过" : "技术校验未执行";
        String business = switch (validation.businessStatus()) {
            case "MATCHED" -> "业务对账一致";
            case "MISMATCH" -> "业务对账有差异";
            default -> "业务对账未执行";
        };
        if (validation.baselineLabel() != null) {
            business += "（" + validation.baselineLabel() + "）";
        }
        return List.of(technical, business);
    }
}

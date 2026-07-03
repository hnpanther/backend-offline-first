package com.hnp.backendofflinefirst.util;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.util.List;

public final class ExcelUtils {

    private ExcelUtils() {
    }

    public static String cellStr(Row row, int col) {
        if (row == null) return null;
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                yield v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    public static boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }

    public static boolean isBlankRow(Row row, int maxCols) {
        if (row == null) return true;
        for (int c = 0; c < maxCols; c++) {
            if (!isEmpty(cellStr(row, c))) return false;
        }
        return true;
    }

    public static void writeWorkbook(HttpServletResponse response,
                                     String filename,
                                     String sheetName,
                                     String[] headers,
                                     List<String[]> rows,
                                     int maxRows,
                                     String truncatedMessage) throws IOException {
        boolean truncated = rows.size() > maxRows;
        List<String[]> limited = truncated ? rows.subList(0, maxRows) : rows;

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(sheetName);
            int rowIdx = 0;
            if (truncated) {
                Row note = sheet.createRow(rowIdx++);
                note.createCell(0).setCellValue(truncatedMessage);
            }
            Row headerRow = sheet.createRow(rowIdx++);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            for (String[] data : limited) {
                Row row = sheet.createRow(rowIdx++);
                for (int i = 0; i < data.length; i++) {
                    row.createCell(i).setCellValue(data[i] != null ? data[i] : "");
                }
            }
            wb.write(response.getOutputStream());
        }
    }

    public static void writeTemplate(HttpServletResponse response, String filename, String[] headers)
            throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("template");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            wb.write(response.getOutputStream());
        }
    }
}

package me.firestone82.solaxstatistics.service.summary;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxstatistics.model.summary.OverallSummary;
import me.firestone82.solaxstatistics.model.summary.SummaryRow;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

@Slf4j
public class SummaryExcelExporter {

    // Header
    private static final String[] headers = new String[]{
            "Date",
            "Import (CEZ)",
            "Import (Rest)",
            "Export (CEZ)",
            "Export (Rest)",
            "Consumption",
            "Yield",
            "Price (EUR)",
            "Price (CZK)",
            "Import Cost (EUR)",
            "Import Cost (CZK)",
            "Export Revenue (EUR)",
            "Export Revenue (CZK)"
    };

    public void exportToExcel(OverallSummary summary, List<SummaryRow> monthlyStatistics, File file) {
        monthlyStatistics.addFirst(summary.getTotal());

        try (Workbook workbook = new XSSFWorkbook()) {
            // Styles
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setBorderBottom(BorderStyle.MEDIUM);
            headerStyle.setBorderLeft(BorderStyle.MEDIUM);
            headerStyle.setBorderTop(BorderStyle.MEDIUM);
            headerStyle.setBorderRight(BorderStyle.MEDIUM);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            Sheet hourlySheet = workbook.createSheet("Hourly");
            hourlySheet.createFreezePane(0, 1);
            writeRows(hourlySheet, summary.getHourly(), headerStyle, "yyyy-mm-dd hh:mm");
            applyColorScaleFormatting(hourlySheet, 7, summary.getHourly().size(), false); // Price EUR column
            applyColorScaleFormatting(hourlySheet, 8, summary.getHourly().size(), false); // Price CZK column
            autoSizeAllColumns(hourlySheet);

            Sheet dailySheet = workbook.createSheet("Daily");
            dailySheet.createFreezePane(0, 1);
            writeRows(dailySheet, summary.getDaily(), headerStyle, "yyyy-mm-dd");
            applyColorScaleFormatting(dailySheet, 9, summary.getDaily().size(), true); // Import Cost EUR column
            applyColorScaleFormatting(dailySheet, 10, summary.getDaily().size(), true); // Import Cost EUR column
            applyColorScaleFormatting(dailySheet, 11, summary.getDaily().size(), false); // Export Revenue EUR column
            applyColorScaleFormatting(dailySheet, 12, summary.getDaily().size(), false); // Export Revenue CZK column
            autoSizeAllColumns(dailySheet);

            Sheet monthlySheet = workbook.createSheet("Monthly");
            monthlySheet.createFreezePane(0, 1);
            writeRows(monthlySheet, monthlyStatistics, headerStyle, "yyyy-mm");
            applyColorScaleFormatting(monthlySheet, 9, monthlyStatistics.size(), true); // Import Cost EUR column
            applyColorScaleFormatting(monthlySheet, 10, monthlyStatistics.size(), true); // Import Cost EUR column
            applyColorScaleFormatting(monthlySheet, 11, monthlyStatistics.size(), false); // Export Revenue EUR column
            applyColorScaleFormatting(monthlySheet, 12, monthlyStatistics.size(), false); // Export Revenue CZK column
            autoSizeAllColumns(monthlySheet);

            try (OutputStream os = Files.newOutputStream(file.toPath())) {
                workbook.write(os);
            }
        } catch (IOException e) {
            log.error("Failed to write Excel file {}: {}", file.getPath(), e.getMessage(), e);
        }
    }

    private void writeRows(Sheet sheet, List<SummaryRow> rows, CellStyle headerStyle, String dateFormat) {
        Row header = sheet.createRow(0);
        IntStream.range(0, headers.length).forEach(i -> writeCell(header, i, headers[i], headerStyle));

        CellStyle rightBorderStyle = sheet.getWorkbook().createCellStyle();
        rightBorderStyle.setBorderRight(BorderStyle.THIN);
        rightBorderStyle.setBorderLeft(BorderStyle.THIN);

        CellStyle dateStyle = sheet.getWorkbook().createCellStyle();
        dateStyle.setDataFormat(sheet.getWorkbook().getCreationHelper().createDataFormat().getFormat(dateFormat));
        dateStyle.setAlignment(HorizontalAlignment.CENTER);
        dateStyle.setBorderRight(BorderStyle.MEDIUM);

        int rowIndex = 1;
        for (SummaryRow summaryRow : rows) {
            Row row = sheet.createRow(rowIndex++);
            int colIndex = 0;

            // Date/DateTime -> Excel date (double)
            writeCell(row, colIndex++, summaryRow.getDate(), dateStyle);
            writeCell(row, colIndex++, summaryRow.getImportCEZ(), "#,###0.000 \"kWh\"", rightBorderStyle, IndexedColors.GREY_40_PERCENT);
            writeCell(row, colIndex++, summaryRow.getImportRest(), "#,###0.000 \"kWh\"", rightBorderStyle, IndexedColors.BLACK);
            writeCell(row, colIndex++, summaryRow.getExportCEZ(), "#,###0.000 \"kWh\"", rightBorderStyle, IndexedColors.GREY_40_PERCENT);
            writeCell(row, colIndex++, summaryRow.getExportRest(), "#,###0.000 \"kWh\"", rightBorderStyle, IndexedColors.BLACK);
            writeCell(row, colIndex++, summaryRow.getConsumption(), "#,###0.000 \"kWh\"", rightBorderStyle, IndexedColors.BLACK);
            writeCell(row, colIndex++, summaryRow.getYield(), "#,###0.000 \"kWh\"", rightBorderStyle, IndexedColors.BLACK);
            writeCell(row, colIndex++, summaryRow.getPriceEUR(), "#,###0.000 \"€\"", rightBorderStyle, IndexedColors.GREY_40_PERCENT);
            writeCell(row, colIndex++, summaryRow.getPriceCZK(), "#,###0.000 \"CZK\"", rightBorderStyle, IndexedColors.BLACK);
            writeCell(row, colIndex++, summaryRow.getImportCostEUR(), "#,###0.000 \"€\"", rightBorderStyle, IndexedColors.GREY_40_PERCENT);
            writeCell(row, colIndex++, summaryRow.getImportCostCZK(), "#,###0.000 \"CZK\"", rightBorderStyle, IndexedColors.BLACK);
            writeCell(row, colIndex++, summaryRow.getExportRevenueEUR(), "#,###0.000 \"€\"", rightBorderStyle, IndexedColors.GREY_40_PERCENT);
            writeCell(row, colIndex++, summaryRow.getExportRevenueCZK(), "#,###0.000 \"CZK\"", rightBorderStyle, IndexedColors.BLACK);
        }
    }

    private <T> Cell writeCell(Row row, int colIndex, T value, @Nullable CellStyle cellStyle) {
        return writeCell(row, colIndex, value, null, cellStyle, IndexedColors.BLACK);
    }

    private <T> Cell writeCell(Row row, int colIndex, T value, @Nullable String dataFormat, @Nullable CellStyle cellStyle, @Nullable IndexedColors color) {
        Cell cell = row.createCell(colIndex);

        switch (value) {
            case String stringValue -> cell.setCellValue(stringValue);
            case Number numberValue -> cell.setCellValue(numberValue.doubleValue());
            case Date dateValue -> cell.setCellValue(dateValue);
            case LocalDateTime ldtValue -> cell.setCellValue(Timestamp.valueOf(ldtValue));
            case LocalDate ldValue -> cell.setCellValue(Date.valueOf(ldValue));
            case Boolean boolValue -> cell.setCellValue(boolValue);
            case null -> cell.setBlank();
            default -> throw new IllegalArgumentException("Unsupported cell value type: " + value.getClass());
        }

        if (cellStyle != null) {
            Workbook wb = row.getSheet().getWorkbook();

            CellStyle style = wb.createCellStyle();
            style.cloneStyleFrom(cellStyle);

            if (color != null) {
                style.setRightBorderColor(color.getIndex());
                style.setLeftBorderColor(color.getIndex());
            }

            if (dataFormat != null) {
                DataFormat format = wb.createDataFormat();
                style.setDataFormat(format.getFormat(dataFormat));
            }

            cell.setCellStyle(style);
        }

        return cell;
    }

    private void autoSizeAllColumns(Sheet sheet) {
        if (sheet.getPhysicalNumberOfRows() == 0) {
            return;
        }

        Row header = sheet.getRow(0);
        if (header == null) return;

        int lastCol = header.getLastCellNum();
        for (int c = 0; c < lastCol; c++) {
            sheet.autoSizeColumn(c);

            int current = sheet.getColumnWidth(c);
            int padding = 256 * 2; // 2 characters padding

            sheet.setColumnWidth(c, Math.min(current + padding, 255 * 256)); // cap at Excel max
        }
    }

    private static void applyColorScaleFormatting(Sheet sheet, int columnIndex, int lastDataRowInclusive, boolean reverseColors) {
        if (lastDataRowInclusive < 1) return;

        SheetConditionalFormatting cf = sheet.getSheetConditionalFormatting();

        // 3-color scale: MIN (light red) → 50th percentile (light yellow) → MAX (light green)
        ConditionalFormattingRule rule = cf.createConditionalFormattingColorScaleRule();
        ColorScaleFormatting csf = rule.getColorScaleFormatting();
        csf.setNumControlPoints(3);

        ConditionalFormattingThreshold[] th = csf.getThresholds();
        th[0].setRangeType(ConditionalFormattingThreshold.RangeType.MIN);
        th[1].setRangeType(ConditionalFormattingThreshold.RangeType.PERCENTILE);
        th[1].setValue(50d);
        th[2].setRangeType(ConditionalFormattingThreshold.RangeType.MAX);

        Color[] colors = csf.getColors();
        ((XSSFColor) colors[0]).setRGB(new byte[]{(byte) 255, (byte) 140, (byte) 140});  // soft red
        ((XSSFColor) colors[1]).setRGB(new byte[]{(byte) 255, (byte) 230, (byte) 150});  // soft yellow
        ((XSSFColor) colors[2]).setRGB(new byte[]{(byte) 185, (byte) 255, (byte) 185});  // soft green

        // If reverseColors is true, swap the colors
        if (reverseColors) {
            Color temp = colors[0];
            colors[0] = colors[2];
            colors[2] = temp;

            csf.setColors(colors);
        }

        CellRangeAddress[] regions = {
                new CellRangeAddress(1, lastDataRowInclusive, columnIndex, columnIndex)
        };
        cf.addConditionalFormatting(regions, rule);
    }

}
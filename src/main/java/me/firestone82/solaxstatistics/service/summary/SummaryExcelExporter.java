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
            "Yield",
            "Consumption",
            "OTE Export Price",
            "",
            "Import (Grid)",
            "Import (Self)",
            "Total Import",
            "Import Cost (Grid)",
            "Import Cost (Self)",
            "Total Import Cost",
            "",
            "Export (Grid)",
            "Export (Self)",
            "Total Export",
            "Export Revenue (Grid)",
            "Export Revenue (Self)",
            "Total Export Revenue",
            "",
            "Self consumption",
            "Savings",
            "Self-use Rate",
            "",
            "Profit/Loss"
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
            applyColorScaleFormatting(hourlySheet, 3, summary.getHourly().size(), false);
            applyColorScaleFormatting(hourlySheet, 7, summary.getHourly().size(), true);
            applyColorScaleFormatting(hourlySheet, 10, summary.getHourly().size(), true);
            applyColorScaleFormatting(hourlySheet, 14, summary.getHourly().size(), false);
            applyColorScaleFormatting(hourlySheet, 17, summary.getHourly().size(), false);
            applyColorScaleFormatting(hourlySheet, 23, summary.getHourly().size(), false);
            autoSizeAllColumns(hourlySheet);

            Sheet dailySheet = workbook.createSheet("Daily");
            dailySheet.createFreezePane(0, 1);
            writeRows(dailySheet, summary.getDaily(), headerStyle, "yyyy-mm-dd");
            applyColorScaleFormatting(dailySheet, 3, summary.getDaily().size(), false);
            applyColorScaleFormatting(dailySheet, 7, summary.getDaily().size(), true);
            applyColorScaleFormatting(dailySheet, 10, summary.getDaily().size(), true);
            applyColorScaleFormatting(dailySheet, 14, summary.getDaily().size(), false);
            applyColorScaleFormatting(dailySheet, 17, summary.getDaily().size(), false);
            applyColorScaleFormatting(dailySheet, 23, summary.getDaily().size(), false);
            autoSizeAllColumns(dailySheet);

            Sheet monthlySheet = workbook.createSheet("Monthly");
            monthlySheet.createFreezePane(0, 1);
            writeRows(monthlySheet, monthlyStatistics, headerStyle, "yyyy-mm");
            applyColorScaleFormatting(monthlySheet, 3, monthlyStatistics.size(), false);
            applyColorScaleFormatting(monthlySheet, 7, monthlyStatistics.size(), true);
            applyColorScaleFormatting(monthlySheet, 10, monthlyStatistics.size(), true);
            applyColorScaleFormatting(monthlySheet, 14, monthlyStatistics.size(), false);
            applyColorScaleFormatting(monthlySheet, 17, monthlyStatistics.size(), false);
            applyColorScaleFormatting(monthlySheet, 23, monthlyStatistics.size(), false);
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

        CellStyle lightBorder = sheet.getWorkbook().createCellStyle();
        lightBorder.setBorderRight(BorderStyle.THIN);

        CellStyle thickBorder = sheet.getWorkbook().createCellStyle();
        thickBorder.setBorderRight(BorderStyle.MEDIUM);

        CellStyle dateStyle = sheet.getWorkbook().createCellStyle();
        dateStyle.setDataFormat(sheet.getWorkbook().getCreationHelper().createDataFormat().getFormat(dateFormat));
        dateStyle.setAlignment(HorizontalAlignment.CENTER);
        dateStyle.setBorderRight(BorderStyle.MEDIUM);

        int rowIndex = 1;
        for (SummaryRow summaryRow : rows) {
            Row row = sheet.createRow(rowIndex++);
            int colIndex = 0;

            double totalImport = summaryRow.getImportGrid() + summaryRow.getImportSelf();
            double totalExport = summaryRow.getExportGrid() + summaryRow.getExportSelf();
            double totalImportCost = summaryRow.getImportCostGrid() + summaryRow.getImportCostSelf();
            double totalExportRevenue = summaryRow.getExportRevenueGrid() + summaryRow.getExportRevenueSelf();
            double profitLoss = (totalExportRevenue - totalImportCost) + summaryRow.getSavings();

            // Date/DateTime -> Excel date (double)
            writeCell(row, colIndex++, summaryRow.getDate(), dateStyle);
            writeCell(row, colIndex++, summaryRow.getYield(), "#,###0.000 \"kWh\"", lightBorder);
            writeCell(row, colIndex++, summaryRow.getConsumption(), "#,###0.000 \"kWh\"", thickBorder);
            writeCell(row, colIndex++, summaryRow.getExportPriceGrid(), "#,###0.000 \"CZK\"", thickBorder);
            writeCell(row, colIndex++, "", thickBorder);
            writeCell(row, colIndex++, summaryRow.getImportGrid(), "#,###0.000 \"kWh\"", lightBorder);
            writeCell(row, colIndex++, summaryRow.getImportSelf(), "#,###0.000 \"kWh\"", lightBorder);
            writeCell(row, colIndex++, totalImport, "#,###0.000 \"kWh\"", thickBorder);
            writeCell(row, colIndex++, summaryRow.getImportCostGrid(), "#,###0.000 \"CZK\"", lightBorder);
            writeCell(row, colIndex++, summaryRow.getImportCostSelf(), "#,###0.000 \"CZK\"", lightBorder);
            writeCell(row, colIndex++, totalImportCost, "#,###0.000 \"CZK\"", thickBorder);
            writeCell(row, colIndex++, "", thickBorder);
            writeCell(row, colIndex++, summaryRow.getExportGrid(), "#,###0.000 \"kWh\"", lightBorder);
            writeCell(row, colIndex++, summaryRow.getExportSelf(), "#,###0.000 \"kWh\"", lightBorder);
            writeCell(row, colIndex++, totalExport, "#,###0.000 \"kWh\"", thickBorder);
            writeCell(row, colIndex++, summaryRow.getExportRevenueGrid(), "#,###0.000 \"CZK\"", lightBorder);
            writeCell(row, colIndex++, summaryRow.getExportRevenueSelf(), "#,###0.000 \"CZK\"", lightBorder);
            writeCell(row, colIndex++, totalExportRevenue, "#,###0.000 \"CZK\"", thickBorder);
            writeCell(row, colIndex++, "", thickBorder);
            writeCell(row, colIndex++, summaryRow.getSelfConsummated(), "#,###0.000 \"kWh\"", lightBorder);
            writeCell(row, colIndex++, summaryRow.getSavings(), "#,###0.000 \"CZK\"", lightBorder);
            writeCell(row, colIndex++, summaryRow.getSelfUsePercentage(), "#,##0.00 \"%\"", lightBorder);
            writeCell(row, colIndex++, "", thickBorder);
            writeCell(row, colIndex++, profitLoss, "#,###0.000 \"CZK\"", thickBorder);
        }
    }

    private <T> Cell writeCell(Row row, int colIndex, T value, @Nullable CellStyle cellStyle) {
        return writeCell(row, colIndex, value, null, cellStyle);
    }

    private <T> Cell writeCell(Row row, int colIndex, T value, @Nullable String dataFormat, @Nullable CellStyle cellStyle) {
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
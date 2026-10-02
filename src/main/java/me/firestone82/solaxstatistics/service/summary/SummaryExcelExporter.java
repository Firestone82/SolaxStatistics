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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // XSSF allows at most 64000 cell styles per workbook and a quarterly month alone has ~70000 styled cells,
    // so every distinct (base style, data format) clone is created once per workbook and shared by its cells
    private final Map<String, CellStyle> cellStyles = new HashMap<>();

    public void exportToExcel(OverallSummary summary, List<SummaryRow> monthlyStatistics, File file) {
        monthlyStatistics.addFirst(summary.getTotal());
        List<SummaryRow> yearlyStatistics = aggregateYearly(monthlyStatistics);

        try (Workbook workbook = new XSSFWorkbook()) {
            cellStyles.clear(); // Styles are only valid in the workbook that created them

            CellStyle headerStyle = createHeaderStyle(workbook);

            writeSheet(workbook, "Quarterly", summary.getQuarterHourly(), headerStyle, "yyyy-mm-dd hh:mm");
            writeSheet(workbook, "Hourly", summary.getHourly(), headerStyle, "yyyy-mm-dd hh:mm");
            writeSheet(workbook, "Daily", summary.getDaily(), headerStyle, "yyyy-mm-dd");
            writeSheet(workbook, "Monthly", monthlyStatistics, headerStyle, "yyyy-mm");
            writeSheet(workbook, "Yearly", yearlyStatistics, headerStyle, "yyyy");

            try (OutputStream os = Files.newOutputStream(file.toPath())) {
                workbook.write(os);
            }
        } catch (IOException e) {
            log.error("Failed to write Excel file {}: {}", file.getPath(), e.getMessage(), e);
        }
    }

    private List<SummaryRow> aggregateYearly(List<SummaryRow> monthlyStatistics) {
        List<SummaryRow> yearlyStatistics = SummaryRow.aggregate(monthlyStatistics, SummaryRow.Granularity.YEAR);
        yearlyStatistics = OverallSummary.preprocessExportSelf(yearlyStatistics, true);
        Collections.reverse(yearlyStatistics);

        return yearlyStatistics;
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setBorderBottom(BorderStyle.MEDIUM);
        headerStyle.setBorderLeft(BorderStyle.MEDIUM);
        headerStyle.setBorderTop(BorderStyle.MEDIUM);
        headerStyle.setBorderRight(BorderStyle.MEDIUM);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        return headerStyle;
    }

    private void writeSheet(Workbook workbook, String name, List<SummaryRow> rows, CellStyle headerStyle, String dateFormat) {
        log.trace("Writing {} rows to sheet {}", rows.size(), name);

        Sheet sheet = workbook.createSheet(name);
        sheet.createFreezePane(0, 1);
        writeRows(sheet, rows, headerStyle, dateFormat);
        colorSheet(rows.size(), sheet);
    }

    private void colorSheet(int dataSize, Sheet sheet) {
        applyColorScaleFormatting(sheet, 3, dataSize, false);
        applyColorScaleFormatting(sheet, 7, dataSize, true);
        applyColorScaleFormatting(sheet, 10, dataSize, true);
        applyColorScaleFormatting(sheet, 14, dataSize, false);
        applyColorScaleFormatting(sheet, 17, dataSize, false);
        applyColorScaleFormatting(sheet, 21, dataSize, false);
        applyColorScaleFormatting(sheet, 23, dataSize, false);
        autoSizeAllColumns(sheet);
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
            writeCell(row, colIndex++, summaryRow.getSelfUsePercentage(), "#,##0.00 \"%\"", thickBorder);
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
            cell.setCellStyle(getCellStyle(row.getSheet().getWorkbook(), cellStyle, dataFormat));
        }

        return cell;
    }

    private CellStyle getCellStyle(Workbook wb, CellStyle baseStyle, @Nullable String dataFormat) {
        return cellStyles.computeIfAbsent(baseStyle.getIndex() + "|" + dataFormat, k -> {
            CellStyle style = wb.createCellStyle();
            style.cloneStyleFrom(baseStyle);

            if (dataFormat != null) {
                DataFormat format = wb.createDataFormat();
                style.setDataFormat(format.getFormat(dataFormat));
            }

            return style;
        });
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
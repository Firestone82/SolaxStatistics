package me.firestone82.solaxstatistics.service.summary;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxstatistics.model.EnergyEntry;
import me.firestone82.solaxstatistics.model.PriceEntry;
import me.firestone82.solaxstatistics.model.StatisticsEntry;
import me.firestone82.solaxstatistics.model.summary.OverallSummary;
import me.firestone82.solaxstatistics.model.summary.SummaryRow;
import me.firestone82.solaxstatistics.serialization.GsonService;
import me.firestone82.solaxstatistics.service.cez.CEZService;
import me.firestone82.solaxstatistics.service.cez.CEZTariff;
import me.firestone82.solaxstatistics.service.ote.OTEService;
import me.firestone82.solaxstatistics.service.smtp.EmailService;
import me.firestone82.solaxstatistics.service.solax.SolaxService;
import me.firestone82.solaxstatistics.utils.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class SummaryService {
    private final SolaxService solaxService;
    private final CEZService cezService;
    private final CEZTariff cezTariff;
    private final OTEService oteService;
    private final EmailService emailService;
    private final File dataDir;

    public SummaryService(
            @Value("${data.directory}") String storagePath,
            @Autowired SolaxService solaxService,
            @Autowired CEZService cezService,
            @Autowired CEZTariff cezTariff,
            @Autowired OTEService oteService,
            @Autowired EmailService emailService
    ) {
        log.info("Initializing Export service");

        this.solaxService = solaxService;
        this.cezService = cezService;
        this.cezTariff = cezTariff;
        this.oteService = oteService;
        this.emailService = emailService;
        this.dataDir = FileUtils.ensureFolderCreated(storagePath, "summary");

        log.info("Initialized Summary service. Data directory: {}", dataDir.getAbsolutePath());
    }

    public Optional<OverallSummary> processSummary(YearMonth yearMonth) {
        log.debug("Processing FVE statistics for {}", yearMonth);

        Optional<Map<LocalDateTime, EnergyEntry>> consumptionData = cezService.getConsumptionHourly(yearMonth);
        Optional<Map<LocalDateTime, StatisticsEntry>> statisticsData = solaxService.getStatisticsHourly(yearMonth);
        Optional<List<PriceEntry>> priceData = oteService.getPrices(yearMonth);

        if (consumptionData.isEmpty() || priceData.isEmpty() || statisticsData.isEmpty()) {
            log.warn("Unable to process data for {}, since scraping failed!", yearMonth);
            return Optional.empty();
        }

        // Summary
        List<SummaryRow> hourlyStatistics = mergeWithPrices(consumptionData.get(), statisticsData.get(), priceData.get());
        List<SummaryRow> monthlyStatistics = getMonthlyHistory(yearMonth);

        OverallSummary summary = new OverallSummary(yearMonth, hourlyStatistics);
        double totalImport = summary.getTotal().getImportGrid() + summary.getTotal().getImportSelf();
        double totalExport = summary.getTotal().getExportGrid() + summary.getTotal().getExportSelf();
        log.info("Summary processing completed for {}. Total consumption/import/export: {}/{}/{} kWh", yearMonth, summary.getTotal().getConsumption(), totalImport, totalExport);

        // Save summary to file
        Optional<File> excelFile = saveToExcel(summary, monthlyStatistics, yearMonth);
        Optional<File> jsonFile = saveToJson(summary.getTotal(), yearMonth);

        if (excelFile.isEmpty() || jsonFile.isEmpty()) {
            log.warn("Failed to save summary files for {}", yearMonth);
            return Optional.empty();
        }

        // Send email with attachments
        sendEmail(yearMonth, summary, List.of(excelFile.get()));

        return Optional.of(summary);
    }

    public Optional<File> saveToExcel(OverallSummary summary, List<SummaryRow> monthlyStatistics, YearMonth yearMonth) {
        String filename = String.format("summary_%s.xlsx", yearMonth);
        log.debug("Saving summary to Excel file: {}", filename);

        File file = new File(dataDir, filename);

        try {
            SummaryExcelExporter exporter = new SummaryExcelExporter();
            exporter.exportToExcel(summary, monthlyStatistics, file);
        } catch (Exception e) {
            log.error("Failed to write summary to Excel file {}: {}", file.getPath(), e.getMessage(), e);
            return Optional.empty();
        }

        log.info("Successfully saved summary to Excel file: {}", file.getAbsolutePath());
        return Optional.of(file);
    }

    public Optional<File> saveToJson(SummaryRow summaryRow, YearMonth yearMonth) {
        String filename = String.format("summary_%s.json", yearMonth);
        log.debug("Saving summary to JSON file: {}", filename);

        File file = new File(dataDir, filename);

        try (FileWriter writer = new FileWriter(file)) {
            GsonService.gson.toJson(summaryRow, writer);
        } catch (IOException e) {
            log.error("Failed to write summary to JSON file {}: {}", file.getPath(), e.getMessage(), e);
            return Optional.empty();
        }

        log.info("Successfully saved summary to JSON file: {}", file.getAbsolutePath());
        return Optional.of(file);
    }

    public void sendEmail(YearMonth yearMonth, OverallSummary summary, List<File> attachments) {
        log.debug("Sending summary email for {}", yearMonth);

        Map<String, Object> variables = new HashMap<>();
        variables.put("date", yearMonth);
        variables.put("year", yearMonth.getYear());
        variables.put("consumption", summary.getTotal().getConsumption());
        variables.put("yield", summary.getTotal().getYield());
        variables.put("importGrid", summary.getTotal().getImportGrid());
        variables.put("exportGrid", summary.getTotal().getExportGrid());
        variables.put("importSelf", summary.getTotal().getImportSelf());
        variables.put("exportSelf", summary.getTotal().getExportSelf());
        variables.put("totalImport", summary.getTotal().getImportGrid() + summary.getTotal().getImportSelf());
        variables.put("totalExport", summary.getTotal().getExportGrid() + summary.getTotal().getExportSelf());
        variables.put("importCostGrid", summary.getTotal().getImportCostGrid());
        variables.put("importCostSelf", summary.getTotal().getImportCostSelf());
        variables.put("exportRevenueGrid", summary.getTotal().getExportRevenueGrid());
        variables.put("exportRevenueSelf", summary.getTotal().getExportRevenueSelf());
        variables.put("totalImportCost", summary.getTotal().getImportCostGrid() + summary.getTotal().getImportCostSelf());
        variables.put("totalExportRevenue", summary.getTotal().getExportRevenueGrid() + summary.getTotal().getExportRevenueSelf());
        variables.put("selfConsummated", summary.getTotal().getSelfConsummated());
        variables.put("savings", summary.getTotal().getSavings());
        variables.put("selfUsePercentage", summary.getTotal().getSelfUsePercentage());
        variables.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        // Round all double values to 3 decimal places
        variables.replaceAll((k, v) -> {
            if (v instanceof Double d) {
                return Math.round(d * 1000.0) / 1000.0;
            }

            return v;
        });

        String subject = "FVE - Monthly report of " + yearMonth;
        emailService.sendEmail("energy-report.html", subject, variables, attachments);
        log.info("Summary email for {} sent successfully", yearMonth);
    }

    public List<SummaryRow> getMonthlyHistory(YearMonth yearMonth) {
        File[] files = dataDir.listFiles((dir, name) -> name.matches("summary_\\d{4}-\\d{2}\\.json"));
        if (files == null) {
            log.warn("No summary files found in directory: {}", dataDir.getAbsolutePath());
            return List.of();
        }

        return Stream.of(files)
                .map(f -> {
                    String datePart = f.getName().replace("summary_", "").replace(".json", "");
                    YearMonth fileYearMonth = YearMonth.parse(datePart);

                    if (fileYearMonth.isAfter(yearMonth) || fileYearMonth.equals(yearMonth)) {
                        return null; // Skip future months
                    }

                    try (FileReader reader = new FileReader(f)) {
                        return GsonService.gson.fromJson(reader, SummaryRow.class);
                    } catch (IOException e) {
                        log.error("Failed to read summary from JSON file {}: {}", f.getAbsolutePath(), e.getMessage(), e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(SummaryRow::getDate).reversed())
                .collect(Collectors.toList());
    }

    private List<SummaryRow> mergeWithPrices(Map<LocalDateTime, EnergyEntry> cezData, Map<LocalDateTime, StatisticsEntry> solaxData, List<PriceEntry> priceData) {
        Map<LocalDateTime, PriceEntry> priceMap = priceData.stream()
                .collect(Collectors.toMap(PriceEntry::getDateTime, p -> p));

        return cezData.entrySet().stream()
                .map(e -> {
                    EnergyEntry energyEntry = e.getValue();
                    PriceEntry priceEntry = priceMap.get(e.getKey());
                    StatisticsEntry statisticsEntry = solaxData.get(e.getKey());

                    if (energyEntry == null || priceEntry == null || statisticsEntry == null) {
                        log.warn("Missing data for date: {}", e.getKey());
                        return null; // Skip this entry if any data is missing
                    }

                    // Solax
                    double consumption = statisticsEntry.getConsumptionMWh() * 1000;
                    double yield = statisticsEntry.getYieldMWh() * 1000;

                    // Prices
                    double importPriceGrid = cezTariff.getImportPrice().getCzk() > 0 ? cezTariff.getImportPrice().getCzk() : priceEntry.getCzkPriceMWh();
                    double importPriceSelf = getDayNightPrice(2.1, 1.1); // CZK/kWh
                    double exportPriceGrid = priceEntry.getCzkPriceMWh() / 1000;
                    double exportPriceSelf = 0.0; // Calculate later by ... (totalSelfExport - totalSelfImport) * 3.0

                    // Import
                    double importGrid = energyEntry.getImportMWh();
                    double importRest = Math.max((statisticsEntry.getImportMWh() * 1000) - importGrid, 0);
                    double importCostGrid = importGrid * importPriceGrid;
                    double importCostSelf = importRest * importPriceSelf;

                    // Export
                    double exportGrid = energyEntry.getExportMWh();
                    double exportRest = Math.max((statisticsEntry.getExportMWh() * 1000) - exportGrid, 0);
                    double exportRevenueGrid = Math.max((exportGrid * exportPriceGrid) - (exportGrid * cezTariff.getExportFee().getCzk()), 0);
                    double exportRevenueSelf = exportRest * exportPriceSelf;

                    // Self consumption
                    double selfConsumed = consumption - importGrid - importRest;
                    double savings = selfConsumed * importPriceGrid;
                    double selfUsePercentage = (selfConsumed / consumption) * 100.0;

                    return SummaryRow.builder()
                            .date(e.getKey())
                            .yield(yield)
                            .consumption(consumption)
                            .exportPriceGrid(exportPriceGrid)
                            .importGrid(importGrid)
                            .importSelf(importRest)
                            .importCostGrid(importCostGrid)
                            .importCostSelf(importCostSelf)
                            .exportGrid(exportGrid)
                            .exportSelf(exportRest)
                            .exportRevenueGrid(exportRevenueGrid)
                            .exportRevenueSelf(exportRevenueSelf)
                            .selfConsummated(selfConsumed)
                            .savings(savings)
                            .selfUsePercentage(selfUsePercentage)
                            .build();
                })
                .sorted(Comparator.comparing(SummaryRow::getDate))
                .collect(Collectors.toList());
    }

    private double getDayNightPrice(double dayPrice, double nightPrice) {
        // Night is interval 0-6 and 19-21
        int currentHour = LocalDateTime.now().getHour();

        if (currentHour < 6 || currentHour >= 19 && currentHour <= 21) {
            return nightPrice;
        }

        return dayPrice;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void test() {
//        processSummary(YearMonth.of(2025, 3));
//        processSummary(YearMonth.of(2025, 5));
//        processSummary(YearMonth.of(2025, 6));
        processSummary(YearMonth.of(2025, 7));
    }
}

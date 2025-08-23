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
        double totalImport = summary.getTotal().getImportCEZ() + summary.getTotal().getImportRest();
        double totalExport = summary.getTotal().getExportCEZ() + summary.getTotal().getExportRest();
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
        Map<String, Object> variables = new HashMap<>();
        variables.put("date", yearMonth);
        variables.put("year", yearMonth.getYear());
        variables.put("consumption", summary.getTotal().getConsumption());
        variables.put("yield", summary.getTotal().getYield());
        variables.put("importCostCZK", summary.getTotal().getImportCostCZK());
        variables.put("importCostEUR", summary.getTotal().getImportCostEUR());
        variables.put("exportRevenueCZK", summary.getTotal().getExportRevenueCZK());
        variables.put("exportRevenueEUR", summary.getTotal().getExportRevenueEUR());
        variables.put("importCEZ", summary.getTotal().getImportCEZ());
        variables.put("importRest", summary.getTotal().getImportRest());
        variables.put("exportCEZ", summary.getTotal().getExportCEZ());
        variables.put("importTotal", summary.getTotal().getImportCEZ() + summary.getTotal().getImportRest());
        variables.put("exportTotal", summary.getTotal().getExportCEZ() + summary.getTotal().getExportRest());
        variables.put("exportRest", summary.getTotal().getExportRest());
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

                    // Import
                    double importPriceEur = cezTariff.getImportPrice().getEur() > 0 ? cezTariff.getImportPrice().getEur() : priceEntry.getEurPriceMWh();
                    double importPriceCzk = cezTariff.getImportPrice().getCzk() > 0 ? cezTariff.getImportPrice().getCzk() : priceEntry.getCzkPriceMWh();
                    double importCez = energyEntry.getImportMWh();
                    double importRest = Math.max((statisticsEntry.getImportMWh() * 1000) - importCez, 0);
                    double importCostEUR = importCez * importPriceEur;
                    double importCostCZK = importCez * importPriceCzk;

                    // Export
                    double exportPriceEur = priceEntry.getEurPriceMWh();
                    double exportPriceCzk = priceEntry.getCzkPriceMWh();
                    double exportCez = energyEntry.getExportMWh();
                    double exportRest = Math.max((statisticsEntry.getExportMWh() * 1000) - exportCez, 0);
                    double exportRevenueEUR = Math.max((exportCez * exportPriceEur / 1000) - (exportCez * cezTariff.getExportFee().getEur()), 0);
                    double exportRevenueCZK = Math.max((exportCez * exportPriceCzk / 1000) - (exportCez * cezTariff.getExportFee().getCzk()), 0);

                    double consumption = statisticsEntry.getConsumptionMWh() * 1000;
                    double yield = statisticsEntry.getYieldMWh() * 1000;

                    return new SummaryRow(e.getKey(), importCez, importRest, exportCez, exportRest, consumption, yield, exportPriceEur, exportPriceCzk, importCostEUR, importCostCZK, exportRevenueEUR, exportRevenueCZK);
                })
                .sorted(Comparator.comparing(SummaryRow::getDate))
                .collect(Collectors.toList());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void test() {
        processSummary(YearMonth.of(2025, 7));
    }
}

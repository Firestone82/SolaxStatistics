package me.firestone82.solaxstatistics.service.cez;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxstatistics.model.EnergyEntry;
import me.firestone82.solaxstatistics.utils.CsvUtils;
import me.firestone82.solaxstatistics.utils.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class CEZService {
    private final CEZScraper cezScraper;
    private final File dataDir;

    public CEZService(
            @Autowired CEZScraper cezScraper,
            @Value("${data.directory}") String storagePath
    ) {
        log.info("Initializing CEZ service");

        this.cezScraper = cezScraper;
        this.dataDir = FileUtils.ensureFolderCreated(storagePath, "cez");

        log.info("Initialized CEZ service. Data directory: {}", dataDir.getAbsolutePath());
    }

    public Optional<Map<LocalDateTime, EnergyEntry>> getConsumptionHourly(YearMonth yearMonth) {
        return getConsumption(yearMonth).map(EnergyEntry::aggregateHourly);
    }

    public Optional<List<EnergyEntry>> getConsumption(YearMonth yearMonth) {
        log.debug("Retrieving CEZ electricity consumption data for {}", yearMonth);

        String fileName = String.format("electricity_%s.csv", yearMonth);
        File file = new File(dataDir, fileName);

        if (file.exists()) {
            log.trace("Found cached file {}, loading data from it", file.getPath());

            Optional<List<EnergyEntry>> foundDataEntries = CsvUtils.loadFromCsv(file, EnergyEntry.class);
            foundDataEntries.ifPresent(prices -> log.debug("Loaded {} data entries from cache", prices.size()));
            return foundDataEntries;
        }

        if (yearMonth.getYear() < 2025) {
            log.warn("CEZ data is only available from 2025 onwards. Requested year: {}", yearMonth.getYear());
            return Optional.of(generateEmptyEntries(yearMonth));
        }

        log.trace("No cached file found, scraping data from CEZ website");
        Optional<List<EnergyEntry>> scrapedDataEntries = cezScraper.scrapeData(yearMonth);

        if (scrapedDataEntries.isPresent()) {
            List<EnergyEntry> dataEntries = scrapedDataEntries.get();
            log.debug("Scraped total of {} consumption entries.", yearMonth);

            CsvUtils.saveToCsv(dataEntries, file);
            log.debug("Saved scraped data to file: {}", file.getAbsolutePath());
        } else {
            log.warn("No data scraped for {}, returning empty list", yearMonth);
        }

        return scrapedDataEntries;
    }

    private List<EnergyEntry> generateEmptyEntries(YearMonth yearMonth) {
        List<EnergyEntry> entries = new ArrayList<>();
        LocalDateTime current = yearMonth.atDay(1).atStartOfDay().withMinute(15);
        LocalDateTime end = yearMonth.atEndOfMonth().atTime(23, 45);

        while (!current.isAfter(end)) {
            entries.add(new EnergyEntry(current, 0.0, 0.0));
            current = current.plusMinutes(15);
        }

        return entries;
    }
}

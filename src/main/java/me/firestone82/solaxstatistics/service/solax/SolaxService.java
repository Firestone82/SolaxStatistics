package me.firestone82.solaxstatistics.service.solax;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxstatistics.model.StatisticsEntry;
import me.firestone82.solaxstatistics.utils.CsvUtils;
import me.firestone82.solaxstatistics.utils.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class SolaxService {
    private final SolaxScraper solaxScraper;
    private final File dataDir;

    public SolaxService(
            @Autowired SolaxScraper solaxScraper,
            @Value("${data.directory}") String storagePath
    ) {
        log.info("Initializing Solax service");

        this.solaxScraper = solaxScraper;
        this.dataDir = FileUtils.ensureFolderCreated(storagePath, "solax");

        log.info("Initialized Solax service. Data directory: {}", dataDir.getAbsolutePath());
    }

    public Optional<Map<LocalDateTime, StatisticsEntry>> getStatisticsHourly(YearMonth yearMonth) {
        return getStatistics(yearMonth).map(StatisticsEntry::aggregateHourly);
    }

    public Optional<List<StatisticsEntry>> getStatistics(YearMonth yearMonth) {
        log.debug("Retrieving Solax electricity consumption data for {}", yearMonth);

        String fileName = String.format("consumption_%s.csv", yearMonth);
        File file = new File(dataDir, fileName);

        if (file.exists()) {
            log.trace("Found cached file {}, loading data from it", file.getPath());

            Optional<List<StatisticsEntry>> foundDataEntries = CsvUtils.loadFromCsv(file, StatisticsEntry.class);
            foundDataEntries.ifPresent(entries -> log.debug("Loaded {} consumption entries from cache", entries.size()));
            return foundDataEntries;
        }

        log.trace("No cached file found, scraping data from Solax website");
        Optional<List<StatisticsEntry>> scrapedDataEntries = solaxScraper.scrapeData(yearMonth);

        if (scrapedDataEntries.isPresent()) {
            List<StatisticsEntry> entries = scrapedDataEntries.get();
            log.debug("Scraped total of {} consumption entries.", yearMonth);

            CsvUtils.saveToCsv(entries, file);
            log.debug("Saved scraped data to file: {}", file.getAbsolutePath());
        } else {
            log.warn("No data scraped for {}, returning empty list", yearMonth);
        }

        return scrapedDataEntries;
    }
}

package me.firestone82.solaxstatistics.service.ote;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxstatistics.model.PriceEntry;
import me.firestone82.solaxstatistics.utils.CsvUtils;
import me.firestone82.solaxstatistics.utils.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class OTEService {
    // The day-ahead market trades quarter-hours since 1 Oct 2025, before that all four quarters of an hour had the same price
    private static final YearMonth FIRST_QUARTER_HOUR_MONTH = YearMonth.of(2025, 10);

    private final OTEScraper oteScraper;
    private final File dataDir;

    public OTEService(
            @Autowired OTEScraper oteScraper,
            @Value("${data.directory}") String storagePath
    ) {
        log.info("Initializing OTE service");

        this.oteScraper = oteScraper;
        this.dataDir = FileUtils.ensureFolderCreated(storagePath, "ote");
        FileUtils.moveMonthFilesToYearFolders(dataDir);

        log.info("Initialized OTE service. Data directory: {}", dataDir.getAbsolutePath());
    }

    /**
     * Returns the quarter-hour prices of the month, from the cache when possible.
     */
    public Optional<List<PriceEntry>> getPrices(YearMonth yearMonth) {
        log.debug("Retrieving OTE prices for {}", yearMonth);

        File file = FileUtils.getMonthFile(dataDir, yearMonth, String.format("prices_%s.csv", yearMonth));
        List<PriceEntry> cachedPrices = loadCachedPrices(file).orElse(null);

        if (cachedPrices != null) {
            // Caches written before the switch hold one price per hour, which is also the price of each of its quarters
            if (hasQuarterHourPrices(cachedPrices) || yearMonth.isBefore(FIRST_QUARTER_HOUR_MONTH)) {
                log.debug("Loaded total of {} price entries.", cachedPrices.size());
                return Optional.of(cachedPrices);
            }

            log.info("Cached file {} holds hourly prices only, scraping the quarter-hour prices", file.getPath());
        }

        log.trace("Scraping OTE prices for {} from the website", yearMonth);
        Optional<List<PriceEntry>> scrapedPrices = oteScraper.scrapePrices(yearMonth);

        if (scrapedPrices.isEmpty()) {
            if (cachedPrices != null) {
                log.warn("No OTE prices scraped for {}, using the cached hourly prices instead", yearMonth);
                return Optional.of(cachedPrices);
            }

            log.warn("No OTE prices scraped for {}", yearMonth);
            return scrapedPrices;
        }

        log.debug("Scraped total of {} price entries for {}", scrapedPrices.get().size(), yearMonth);

        // The current month is still missing its upcoming days, caching it would keep them missing for good
        if (yearMonth.isBefore(YearMonth.now())) {
            CsvUtils.saveToCsv(scrapedPrices.get(), file);
            log.debug("Saved scraped prices to file: {}", file.getAbsolutePath());
        } else {
            log.debug("Not caching prices of {}, the month is not complete yet", yearMonth);
        }

        return scrapedPrices;
    }

    private Optional<List<PriceEntry>> loadCachedPrices(File file) {
        if (!file.exists()) {
            log.trace("No cached file {} found", file.getPath());
            return Optional.empty();
        }

        log.trace("Found cached file {}, loading data from it", file.getPath());
        Optional<List<PriceEntry>> cachedPrices = CsvUtils.loadFromCsv(file, PriceEntry.class);

        if (cachedPrices.isEmpty()) {
            log.warn("Unable to read cached file {}, scraping the prices again", file.getPath());
        }

        return cachedPrices;
    }

    /**
     * Caches written before the switch to quarter-hour prices hold one entry per hour only, all on minute 0.
     */
    private static boolean hasQuarterHourPrices(List<PriceEntry> prices) {
        return prices.stream().anyMatch(price -> price.getDateTime().getMinute() != 0);
    }
}

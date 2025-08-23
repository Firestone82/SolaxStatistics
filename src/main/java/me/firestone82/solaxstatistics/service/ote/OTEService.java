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
    private final OTEScraper oteScraper;
    private final File dataDir;

    public OTEService(
            @Autowired OTEScraper oteScraper,
            @Value("${data.directory}") String storagePath
    ) {
        log.info("Initializing OTE service");

        this.oteScraper = oteScraper;
        this.dataDir = FileUtils.ensureFolderCreated(storagePath, "ote");

        log.info("Initialized OTE service. Data directory: {}", dataDir.getAbsolutePath());
    }

    public Optional<List<PriceEntry>> getPrices(YearMonth yearMonth) {
        log.debug("Retrieving ote history prices for {}", yearMonth);

        String filename = String.format("prices_%s.csv", yearMonth);
        File file = new File(dataDir, filename);

        if (file.exists()) {
            log.trace("Found cached file {}, loading data from it", file.getPath());

            Optional<List<PriceEntry>> foundPriceEntries = CsvUtils.loadFromCsv(file, PriceEntry.class);
            foundPriceEntries.ifPresent(prices -> log.debug("Loaded total of {} price entries.", prices.size()));
            return foundPriceEntries;
        }

        log.trace("No cached file found, scraping data from OTE website");
        Optional<List<PriceEntry>> scrapedPriceEntries = oteScraper.scrapePrices(yearMonth);

        if (scrapedPriceEntries.isPresent()) {
            List<PriceEntry> priceEntries = scrapedPriceEntries.get();
            log.debug("Scraped total of {} price entries.", yearMonth);

            CsvUtils.saveToCsv(priceEntries, file);
            log.debug("Saved scraped data to file: {}", file.getAbsolutePath());
        } else {
            log.warn("No data scraped for {}, returning empty list", yearMonth);
        }

        return scrapedPriceEntries;
    }
}

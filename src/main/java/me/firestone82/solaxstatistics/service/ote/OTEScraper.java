package me.firestone82.solaxstatistics.service.ote;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxstatistics.model.PriceEntry;
import me.firestone82.solaxstatistics.configuration.ote.OTEProperties;
import me.firestone82.solaxstatistics.utils.NumberUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Scrapes the OTE day-ahead prices from the day pages of spotovaelektrina.cz. Those are static HTML pages,
 * so plain HTTP + Jsoup is enough and no browser is needed.
 */
@Slf4j
@Service
public class OTEScraper {

    // Since 1 Oct 2025 the day-ahead market trades quarter-hours. For older days the page repeats each hourly price
    // for all four quarters, and DST days are listed with 96 rows as well.
    private static final int QUARTERS_PER_DAY = 96;
    private static final int FETCH_ATTEMPTS = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(2);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_AGENT = "SolaxStatistics/1.0 (OTE price scraper; Java HttpClient)";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("H:mm");

    private final String dayPricesUrl;

    public OTEScraper(OTEProperties properties) {
        this.dayPricesUrl = properties.getBaseUrl() + "/denni-ceny/";
    }

    /**
     * Scrapes the quarter-hour prices of every day of the month up to today. Each entry is dated by the start
     * of its quarter-hour.
     *
     * @return all prices of the month, or empty when any of its days could not be scraped
     */
    public Optional<List<PriceEntry>> scrapePrices(YearMonth yearMonth) {
        LocalDate firstDay = yearMonth.atDay(1);
        // Tomorrow's prices are only published around midday, a missing day would fail the whole month
        LocalDate lastDay = Collections.min(List.of(yearMonth.atEndOfMonth(), LocalDate.now()));

        if (lastDay.isBefore(firstDay)) {
            log.warn("No OTE prices exist yet for {}, it is in the future", yearMonth);
            return Optional.empty();
        }

        int totalDays = lastDay.getDayOfMonth();
        List<PriceEntry> prices = new ArrayList<>(totalDays * QUARTERS_PER_DAY);
        log.debug("Scraping OTE prices for {} ({} days) from {}", yearMonth, totalDays, dayPricesUrl);
        long startNanos = System.nanoTime();

        try (HttpClient client = createClient()) {
            for (LocalDate date = firstDay; !date.isAfter(lastDay); date = date.plusDays(1)) {
                log.debug("OTE: day {}/{}: scraping prices for {}", date.getDayOfMonth(), totalDays, date);

                String html = fetchDayPage(client, date);
                prices.addAll(parseDayPrices(date, html));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Scraping of OTE prices for {} was interrupted", yearMonth);
            return Optional.empty();
        } catch (Exception e) {
            // A partial month would be cached as if it was complete, so a single failed day fails the whole month
            log.error("Scraping of OTE prices for {} failed: {}", yearMonth, e.getMessage(), e);
            return Optional.empty();
        }

        log.debug("Scraped {} OTE price entries for {} in {} ms", prices.size(), yearMonth, Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        return Optional.of(prices);
    }

    private static HttpClient createClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Downloads the day page, retrying failed attempts with an increasing backoff.
     */
    private String fetchDayPage(HttpClient client, LocalDate date) throws IOException, InterruptedException {
        URI uri = URI.create(dayPricesUrl + date);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        IOException lastFailure = null;
        for (int attempt = 1; attempt <= FETCH_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    log.trace("Fetched {} ({} chars) on attempt {}", uri, response.body().length(), attempt);
                    return response.body();
                }

                lastFailure = new IOException("HTTP " + response.statusCode() + " from " + uri);
            } catch (IOException e) {
                lastFailure = e;
            }

            if (attempt < FETCH_ATTEMPTS) {
                Duration backoff = RETRY_BACKOFF.multipliedBy(attempt);
                log.debug("Attempt {}/{} to fetch {} failed ({}), retrying in {} ms", attempt, FETCH_ATTEMPTS, uri, lastFailure, backoff.toMillis());
                Thread.sleep(backoff.toMillis());
            }
        }

        throw new IOException("Failed to fetch " + uri + " after " + FETCH_ATTEMPTS + " attempts", lastFailure);
    }

    /**
     * Parses the price table of a day page. Its rows hold the quarter-hour start time, the CZK/MWh price
     * (e.g. "-1 305 Kč", with a non-breaking space as the thousands separator) and the EUR/MWh price (e.g. "97,21 €").
     */
    private List<PriceEntry> parseDayPrices(LocalDate date, String html) {
        Element table = Jsoup.parse(html).getElementById("prices");
        if (table == null) {
            throw new IllegalStateException("Price table not found on the page for " + date);
        }

        Map<LocalTime, PriceEntry> prices = new LinkedHashMap<>();
        for (Element row : table.select("tr:has(td)")) {
            Elements cells = row.select("td");
            if (cells.size() < 3) {
                log.warn("Skipping price row of {} with {} cells: '{}'", date, cells.size(), row.text());
                continue;
            }

            LocalTime time;
            try {
                time = LocalTime.parse(cells.get(0).text().trim(), TIME_FORMATTER);
            } catch (DateTimeParseException e) {
                log.warn("Skipping price row of {} with invalid time: '{}'", date, row.text());
                continue;
            }

            Double czkPrice = NumberUtils.parseNumber(cells.get(1).text());
            Double eurPrice = NumberUtils.parseNumber(cells.get(2).text());
            if (czkPrice == null || eurPrice == null) {
                log.warn("Skipping price row of {} with invalid prices: '{}'", date, row.text());
                continue;
            }

            // Prices are looked up by their time later on, a duplicated time must not end up in the result twice
            if (prices.putIfAbsent(time, new PriceEntry(date.atTime(time), czkPrice, eurPrice)) != null) {
                log.warn("Skipping duplicated price row of {} for {}", date, time);
            }
        }

        if (prices.isEmpty()) {
            throw new IllegalStateException("No prices listed on the page for " + date);
        }

        if (prices.size() != QUARTERS_PER_DAY) {
            log.warn("Expected {} quarter-hour prices for {}, but found {}", QUARTERS_PER_DAY, date, prices.size());
        }

        log.trace("Parsed {} prices for {}", prices.size(), date);
        return new ArrayList<>(prices.values());
    }
}

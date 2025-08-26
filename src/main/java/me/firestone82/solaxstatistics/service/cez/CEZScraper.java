package me.firestone82.solaxstatistics.service.cez;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxstatistics.model.EnergyEntry;
import me.firestone82.solaxstatistics.utils.FileUtils;
import me.firestone82.solaxstatistics.utils.NumberUtils;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
@Component
public class CEZScraper {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private final String portalUrl;
    private final String exportUrl;
    private final String username;
    private final String password;
    private final long meterId;

    public CEZScraper(
            @Value("${cez.url.portal}") String portalUrl,
            @Value("${cez.url.export}") String exportUrl,
            @Value("${cez.meterId}") long meterId,
            @Value("${cez.credentials.username}") String username,
            @Value("${cez.credentials.password}") String password
    ) {
        this.portalUrl = portalUrl;
        this.exportUrl = exportUrl;
        this.meterId = meterId;
        this.username = username;
        this.password = password;
    }

    public Optional<List<EnergyEntry>> scrapeData(YearMonth yearMonth) {
        List<EnergyEntry> entries = new ArrayList<>();

        LocalDateTime startDate = yearMonth.atDay(1).atStartOfDay();
        String from = URLEncoder.encode(startDate.format(DATE_FORMATTER), StandardCharsets.UTF_8);

        LocalDateTime endDate = yearMonth.atEndOfMonth().atTime(23, 45, 0);
        String to = URLEncoder.encode(endDate.format(DATE_FORMATTER), StandardCharsets.UTF_8);

        String targetUrl = exportUrl + "?format=csv-simple&idAssembly=-1003&intervalFrom=" + from + "%2000%3A00&intervalTo=" + to + "%2023%3A45&electrometerId=" + meterId;
        log.debug("Scraping CEZ data for {} from {}", yearMonth, targetUrl);

        // Temporary directory for downloads
        Path tempDir = FileUtils.createTempFolder("cez_scraper").orElseThrow();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--disable-gpu");
        options.addArguments("--no-sandbox", "--disable-dev-shm-usage");
        options.setExperimentalOption("prefs", Map.of(
                "download.default_directory", tempDir.toFile().getAbsolutePath(),
                "download.prompt_for_download", false,
                "safebrowsing.enabled", true
        ));

        WebDriver driver = new ChromeDriver(options);
        try {
            WebDriverWait wait = new WebDriverWait(driver, java.time.Duration.ofSeconds(20));

            driver.get(portalUrl);
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#mat-input-0"))).sendKeys(username);
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#mat-input-1"))).sendKeys(password + Keys.RETURN);

            try {
                wait.until(ExpectedConditions.elementToBeClickable(By.id("CybotCookiebotDialogBodyButtonDecline"))).click();
            } catch (Exception ignored) {
            }

            List<WebElement> buttons = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(By.cssSelector("body > dip-root dip-layout-anonymous button")));
            buttons.get(1).click();

            // Wait for the page to load
            Thread.sleep(5000);

            ((JavascriptExecutor) driver).executeScript("window.open('about:blank','_blank');");
            driver.switchTo().window(driver.getWindowHandles().toArray()[1].toString());
            driver.get(targetUrl);

            // Wait for the download to complete
            Thread.sleep(10000);

            try (Stream<Path> stream = Files.list(tempDir)) {
                Optional<Path> downloaded = stream
                        .filter(p -> p.getFileName().toString().equalsIgnoreCase("pnd_export.csv"))
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified()));

                if (downloaded.isPresent()) {
                    Path path = downloaded.get();

                    List<String> lines = Files.readAllLines(path, Charset.forName("ISO-8859-2"));
                    boolean skipHeader = false;

                    for (String line : lines) {
                        if (!skipHeader) {
                            skipHeader = true;
                            continue;
                        }

                        try {
                            String[] parts = line.split(";");

                            // Remove quotes and trim whitespace
                            for (int i = 0; i < parts.length; i++) {
                                parts[i] = parts[i].replace("\"", "").trim();
                            }

                            LocalDateTime dateTime = LocalDateTime.parse(parts[0], DATE_TIME_FORMATTER);
                            double importVal = NumberUtils.parseNumber(parts[1]);
                            double exportVal = NumberUtils.parseNumber(parts[3]);

                            entries.add(new EnergyEntry(dateTime, importVal, exportVal));
                        } catch (Exception e) {
                            log.debug("Skipping invalid row: {}", line, e);
                        }
                    }

                    // Clean up downloaded file
                    Files.deleteIfExists(path);
                }
            }
        } catch (Exception e) {
            log.error("Error during CEZ scraping: {}", e.getMessage(), e);
            return Optional.empty();
        } finally {
            driver.quit();
        }

        return Optional.of(entries);
    }
}
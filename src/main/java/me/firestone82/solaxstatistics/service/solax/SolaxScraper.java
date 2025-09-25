package me.firestone82.solaxstatistics.service.solax;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxstatistics.model.StatisticsEntry;
import org.apache.poi.ss.usermodel.*;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Slf4j
@Component
public class SolaxScraper {

    private final String portalUrl;
    private final String reportUrl;
    private final String exportedDataUrl;
    private final String username;
    private final String password;

    @Setter
    private File downloadDir;

    public SolaxScraper(
            @Value("${solax.url.portal}") String portalUrl,
            @Value("${solax.url.report}") String reportUrl,
            @Value("${solax.url.exportedData}") String exportedDataUrl,
            @Value("${solax.credentials.username}") String username,
            @Value("${solax.credentials.password}") String password
    ) {
        this.portalUrl = portalUrl;
        this.reportUrl = reportUrl;
        this.exportedDataUrl = exportedDataUrl;
        this.username = username;
        this.password = password;
    }

    public Optional<List<StatisticsEntry>> scrapeData(YearMonth yearMonth) {
        log.debug("Scraping Solax data for {}", yearMonth);

        Path tempDir;
        if (downloadDir != null) {
            tempDir = downloadDir.toPath();
            log.trace("Using provided download directory: {}", tempDir);
        } else {
            log.trace("No download directory provided, creating a temporary one");
            tempDir = createTempDownloadDir().orElse(null);
        }

        if (tempDir == null) {
            log.error("No valid download directory available, aborting");
            return Optional.empty();
        }

        try {
            Files.createDirectories(tempDir);
            log.trace("Ensured download directory exists: {}", tempDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to create download directory {}: {}", tempDir, e.getMessage(), e);
            return Optional.empty();
        }

        ChromeOptions options = new ChromeOptions();
//        options.addArguments("--headless=new", "--disable-gpu");
        options.addArguments("--no-sandbox", "--disable-dev-shm-usage");
        Map<String, Object> chromePrefs = Map.of(
                "download.default_directory", tempDir.toFile().getAbsolutePath(),
                "download.prompt_for_download", false,
                "safebrowsing.enabled", true
        );
        options.setExperimentalOption("prefs", chromePrefs);
        log.trace("ChromeOptions prepared with prefs: {}", chromePrefs);

        WebDriver driver = new ChromeDriver(options);
        log.trace("ChromeDriver started");

        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
            log.trace("WebDriverWait created with timeout: {} seconds", 20);

            long overallStartNanos = System.nanoTime();

            log.debug("Step 1/4: Logging in");
            login(driver, wait);
            traceSleep(5000, "after login to allow page load");

            log.debug("Step 2/4: Requesting monthly export for {}", yearMonth);
            requestMonthlyExport(driver, wait, yearMonth);
            traceSleep(2000, "after export request");

            log.debug("Step 3/4: Waiting for export to complete");
            boolean completed = waitUntilExportCompleted(driver, wait, Duration.ofMinutes(3));
            if (!completed) {
                log.warn("Timed out waiting for export to complete");
                return Optional.empty();
            }

            log.debug("Step 4/4: Downloading the exported report");
            By FIRST_DOWNLOAD_ICON = By.cssSelector("#container > div > div.base-box > div.body > div > div.arco-table.arco-table-size-large.arco-table-border.arco-table-hover.arco-table-type-selection > div > div > div > table > tbody > tr:nth-child(1) > td:nth-child(8) > span > span > i.iconfont.icon-xiazai.success");
            log.trace("Waiting for first download icon to be clickable: {}", FIRST_DOWNLOAD_ICON);
            wait.until(ExpectedConditions.elementToBeClickable(FIRST_DOWNLOAD_ICON)).click();
            log.debug("Clicked first download icon to trigger file download");

            Path downloaded = waitForLatestDownload(tempDir, "Plant Reports", Duration.ofSeconds(30)).orElse(null);
            if (downloaded == null) {
                log.warn("No exported file found in {}", tempDir);
                return Optional.empty();
            }
            log.debug("Latest downloaded file detected: {}", downloaded.getFileName());

            List<StatisticsEntry> entries = parseExcel(downloaded);
            log.debug("Scraped {} entries for {}", entries.size(), yearMonth);

            long overallElapsedMs = Duration.ofNanos(System.nanoTime() - overallStartNanos).toMillis();
            log.info("Solax scraping completed in {} ms for {}", overallElapsedMs, yearMonth);

            return Optional.of(entries);
        } catch (Exception e) {
            log.error("Error during Solax scraping: {}", e.getMessage(), e);
            return Optional.empty();
        } finally {
            try {
                log.trace("Quitting WebDriver");
                driver.quit();
            } catch (Exception ignore) {
                log.trace("Ignoring exception during WebDriver quit");
            }
        }
    }

    private void login(WebDriver driver, WebDriverWait wait) {
        log.trace("Login: navigating to portal URL");
        navigate(driver, portalUrl, wait);

        By USERNAME_INPUT = By.xpath("//*[@id=\"app\"]/div/div[2]/div[3]/div/div[3]/span[1]/input");
        By PASSWORD_INPUT = By.xpath("//*[@id=\"app\"]/div/div[2]/div[3]/div/div[4]/span/input");
        By AGREE_CHECKBOX = By.xpath("//*[@id=\"agreeMent\"]/span[1]");
        By LOGIN_BUTTON = By.xpath("//*[@id=\"app\"]/div/div[2]/div[3]/div/div[6]");

        log.trace("Waiting for username input: {}", USERNAME_INPUT);
        wait.until(ExpectedConditions.presenceOfElementLocated(USERNAME_INPUT)).sendKeys(username);

        log.trace("Waiting for password input: {}", PASSWORD_INPUT);
        wait.until(ExpectedConditions.presenceOfElementLocated(PASSWORD_INPUT)).sendKeys(password);

        log.trace("Clicking agree checkbox: {}", AGREE_CHECKBOX);
        wait.until(ExpectedConditions.elementToBeClickable(AGREE_CHECKBOX)).click();

        log.trace("Clicking login button: {}", LOGIN_BUTTON);
        wait.until(ExpectedConditions.elementToBeClickable(LOGIN_BUTTON)).click();
    }

    private void requestMonthlyExport(WebDriver driver, WebDriverWait wait, YearMonth yearMonth) throws InterruptedException {
        log.trace("Navigating to report URL for export");
        navigate(driver, reportUrl, wait);

        By ADVANCED_EXPORT_BUTTON = By.xpath("//*[@id=\"container\"]/div[2]/div/div/div[1]/div[2]/button[2]");
        log.trace("Waiting for advanced export button: {}", ADVANCED_EXPORT_BUTTON);
        wait.until(ExpectedConditions.elementToBeClickable(ADVANCED_EXPORT_BUTTON)).click();
        traceSleep(250, "after opening advanced export dialog");

        By DATE_INPUT = By.xpath("//*[@id=\"time\"]/div/div/div/div[1]/input");
        log.trace("Waiting for date input: {}", DATE_INPUT);
        wait.until(ExpectedConditions.elementToBeClickable(DATE_INPUT)).click();
        traceSleep(1000, "allow date picker to render");

        By PREV_MONTH_BTN = By.cssSelector("body > div:nth-child(15) > div > div > div > div > div.arco-picker-range > div > div:nth-child(1) > div > div.arco-picker-header > div:nth-child(2)");
        By YEAR_TEXT = By.cssSelector("body > div:nth-child(15) > div > div > div > div > div.arco-picker-range > div > div:nth-child(1) > div > div.arco-picker-header > div.arco-picker-header-title > span:first-child");
        By MONTH_TEXT = By.cssSelector("body > div:nth-child(15) > div > div > div > div > div.arco-picker-range > div > div:nth-child(1) > div > div.arco-picker-header > div.arco-picker-header-title > span:nth-child(3)");

        WebElement yearText = wait.until(ExpectedConditions.presenceOfElementLocated(YEAR_TEXT));
        WebElement monthText = wait.until(ExpectedConditions.presenceOfElementLocated(MONTH_TEXT));
        String monthTwoDigits = "%02d".formatted(yearMonth.getMonthValue());
        int safetyGuard = 0;

        log.trace("Target month/year: {}/{}", monthTwoDigits, yearMonth.getYear());

        // Move calendar to desired month
        while ((!monthText.getText().equalsIgnoreCase(monthTwoDigits) || !yearText.getText().equalsIgnoreCase(String.valueOf(yearMonth.getYear()))) && safetyGuard++ < 24) {
            log.trace("Calendar currently at month/year: {}/{}. Clicking previous month.", monthText.getText(), yearText.getText());
            wait.until(ExpectedConditions.elementToBeClickable(PREV_MONTH_BTN)).click();
            traceSleep(250, "after calendar month change");
            yearText = wait.until(ExpectedConditions.presenceOfElementLocated(YEAR_TEXT));
            monthText = wait.until(ExpectedConditions.presenceOfElementLocated(MONTH_TEXT));
        }

        if (safetyGuard >= 24) {
            log.warn("Safety guard hit while selecting month; calendar might not be responding");
        } else {
            log.trace("Calendar positioned at target month/year: {}/{}", monthTwoDigits, yearMonth.getYear());
        }

        By CALENDAR_GRID = By.cssSelector("body > div:nth-child(15) > div > div > div > div > div.arco-picker-range > div > div:nth-child(1) > div > div.arco-picker-body");
        WebElement grid = wait.until(ExpectedConditions.visibilityOfElementLocated(CALENDAR_GRID));
        List<WebElement> cells = grid.findElements(By.className("arco-picker-cell-in-view"));

        log.trace("Found {} in-view calendar cells before filtering disabled", cells.size());

        cells.removeIf(cell -> {
            String classAttr = cell.getAttribute("class");
            return classAttr != null && classAttr.contains("arco-picker-cell-disabled");
        });
        log.trace("Remaining {} selectable cells after filtering disabled", cells.size());

        if (cells.isEmpty()) {
            throw new IllegalStateException("Calendar grid has no in-view cells");
        }

        log.trace("Selecting first and last day cells");
        cells.getFirst().findElement(By.className("arco-picker-date")).click();
        cells.getLast().findElement(By.className("arco-picker-date")).click();
        traceSleep(1000, "after selecting date range");

        By EXPORT_CONFIRM = By.xpath("/html/body/div[8]/div[2]/div[3]/button[2]");
        log.trace("Clicking export confirm button: {}", EXPORT_CONFIRM);
        wait.until(ExpectedConditions.elementToBeClickable(EXPORT_CONFIRM)).click();
        traceSleep(1000, "after export confirmation");
    }

    private boolean waitUntilExportCompleted(WebDriver driver, WebDriverWait wait, Duration timeout) throws InterruptedException {
        log.trace("Navigating to exported data URL to poll status");
        navigate(driver, exportedDataUrl, wait);

        driver.navigate().refresh();
        traceSleep(1000, "after initial refresh on export page");

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        long iteration = 0L;
        By EXPORT_STATUS_CELL = By.cssSelector("#container > div > div.base-box > div.body > div > div.arco-table.arco-table-size-large.arco-table-border.arco-table-hover.arco-table-type-selection > div > div > div > table > tbody > tr:nth-child(1) > td:nth-child(7) > span > span > span");

        while (System.nanoTime() < deadlineNanos) {
            iteration++;
            TimeUnit.SECONDS.sleep(1); // Give the page time to update

            try {
                String statusText = driver.findElement(EXPORT_STATUS_CELL).getText();
                log.trace("Poll {}: current export status text = '{}'", iteration, statusText);

                if ("Export completed".equalsIgnoreCase(statusText)) {
                    log.debug("Export is completed after {} polls", iteration);
                    return true;
                }
            } catch (Exception ex) {
                log.trace("Poll {}: unable to read export status element ({}). Will retry.", iteration, ex.getMessage());
            }

            TimeUnit.SECONDS.sleep(5);
            log.trace("Refreshing export page (poll {}) to get latest status", iteration);
            driver.navigate().refresh();
        }

        log.warn("Export did not complete within timeout of {} seconds", timeout.toSeconds());
        return false;
    }

    private Optional<Path> waitForLatestDownload(Path dir, String prefix, Duration timeout) {
        log.trace("Waiting for latest download in directory '{}' with prefix '{}' (timeout {}s)", dir.toAbsolutePath(), prefix, timeout.toSeconds());
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Path bestCandidate = null;
        long bestMtime = Long.MIN_VALUE;

        while (System.nanoTime() < deadlineNanos) {
            try (Stream<Path> stream = Files.list(dir)) {
                bestCandidate = stream
                        .filter(p -> p.getFileName().toString().startsWith(prefix))
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                        .orElse(null);

                if (bestCandidate != null) {
                    long mtime = bestCandidate.toFile().lastModified();
                    long sizeBytes = bestCandidate.toFile().length();

                    log.trace("Current best download candidate: name='{}', mtime={}, size={}B", bestCandidate.getFileName(), mtime, sizeBytes);

                    if (mtime > bestMtime) {
                        bestMtime = mtime;

                        if (bestCandidate.toString().toLowerCase().endsWith(".xlsx") && sizeBytes > 0) {
                            log.debug("Detected completed .xlsx download: {}", bestCandidate.getFileName());
                            return Optional.of(bestCandidate);
                        }
                    }
                } else {
                    log.trace("No files currently matching prefix '{}' in {}", prefix, dir);
                }
            } catch (IOException ioException) {
                log.trace("IOException while listing downloads: {}", ioException.getMessage());
            }

            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException interruptedException) {
                log.error("Interrupted while waiting for download: {}", interruptedException.getMessage(), interruptedException);
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (bestCandidate != null) {
            log.debug("Returning last seen candidate after timeout: {}", bestCandidate.getFileName());
        } else {
            log.debug("No download candidate found before timeout");
        }

        return Optional.ofNullable(bestCandidate);
    }

    private List<StatisticsEntry> parseExcel(Path path) {
        log.debug("Parsing Excel file: {}", path.getFileName());
        List<StatisticsEntry> entries = new ArrayList<>();
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        try (Workbook workbook = WorkbookFactory.create(path.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            int rowIndex = 0;

            StatisticsEntry previousEntry = null;
            LocalDateTime previousDate = null;

            for (Row row : sheet) {
                rowIndex++;
                if (rowIndex < 3) {
                    if (log.isTraceEnabled()) {
                        log.trace("Skipping header row {}", rowIndex);
                    }

                    continue; // skip header + sub-header
                }

                String tsString = row.getCell(1).getStringCellValue();
                LocalDateTime timestamp = LocalDateTime.parse(tsString, timeFormatter);

                if (timestamp.toLocalTime().equals(LocalTime.MIDNIGHT)) {
                    log.warn("Skipping midnight entry at row {} - {}", rowIndex, tsString);
                    continue;
                }

                if (previousDate != null && previousDate.getDayOfMonth() != timestamp.getDayOfMonth()) {
                    if (log.isTraceEnabled()) {
                        log.trace("Day changed {} -> {}, resetting previousEntry", previousDate, timestamp);
                    }

                    previousEntry = null;
                }
                previousDate = timestamp;

                double yieldMWh = processNumericCell(row.getCell(2)) / 1000d;
                double exportMWh = processNumericCell(row.getCell(4)) / 1000d;
                double consumptionMWh = processNumericCell(row.getCell(5)) / 1000d;
                double importMWh = processNumericCell(row.getCell(6)) / 1000d;

                if (log.isTraceEnabled()) {
                    log.trace(
                            "Row {} parsed cumulative: ts={}, yieldMWh={}, exportMWh={}, consumptionMWh={}, importMWh={}",
                            rowIndex, timestamp, yieldMWh, exportMWh, consumptionMWh, importMWh
                    );
                }

                StatisticsEntry current = new StatisticsEntry(timestamp, yieldMWh, exportMWh, consumptionMWh, importMWh);
                StatisticsEntry currentCopy = current.clone();

                if (previousEntry != null) {
                    current.subtract(previousEntry);
                    if (log.isTraceEnabled()) {
                        log.trace(
                                "Row {} delta after subtracting previous: yieldMWh={}, exportMWh={}, consumptionMWh={}, importMWh={}",
                                rowIndex, current.getYieldMWh(), current.getExportMWh(), current.getConsumptionMWh(), current.getImportMWh()
                        );
                    }
                }

                previousEntry = currentCopy;
                entries.add(current);
            }
        } catch (Exception e) {
            log.error("Failed to parse Excel {}: {}", path, e.getMessage(), e);
        }

        log.debug("Parsed {} entries from {}", entries.size(), path.getFileName());
        return entries;
    }

    private double processNumericCell(Cell cell) {
        if (cell == null) {
            return 0d;
        }
        DataFormatter formatter = new DataFormatter();

        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                double numericValue = cell.getNumericCellValue();
                if (log.isTraceEnabled()) {
                    log.trace("Numeric cell value: {}", numericValue);
                }
                return numericValue;
            }

            String stringValue = formatter.formatCellValue(cell);
            if (stringValue == null || stringValue.isBlank()) {
                if (log.isTraceEnabled()) {
                    log.trace("Blank cell treated as 0");
                }
                return 0d;
            }

            double parsed = Double.parseDouble(stringValue.replace(',', '.'));
            if (log.isTraceEnabled()) {
                log.trace("Parsed string cell '{}' -> {}", stringValue, parsed);
            }
            return parsed;
        } catch (Exception e) {
            log.warn("Non-numeric cell value '{}', defaulting to 0", formatter.formatCellValue(cell));
            return 0d;
        }
    }

    private void navigate(WebDriver driver, String url, WebDriverWait wait) {
        long startNanos = System.nanoTime();
        String expectedPath = getLastFragmentPathSegment(url).orElse(url);
        log.trace("Navigate: GET {} (expectedPath='{}')", url, expectedPath);
        driver.get(url);

        try {
            wait.until(webDriver -> {
                String currentUrl = webDriver.getCurrentUrl();
                boolean matches = currentUrl != null && currentUrl.contains(expectedPath);

                if (log.isTraceEnabled()) {
                    log.trace("Waiting for URL to contain path '{}': current='{}' match={}", expectedPath, currentUrl, matches);
                }

                return matches;
            });

            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            log.debug("Navigation satisfied (path match) in {} ms. Landed at URL: {}", elapsedMs, driver.getCurrentUrl());
        } catch (Exception e) {
            log.warn("Navigation wait failed for URL '{}' with path '{}': {}", url, expectedPath, e.getMessage());
        }

        try {
            traceSleep(2000, "post-navigation settling");
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for page to load: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
        }
    }

    public static Optional<String> getLastFragmentPathSegment(String urlString) {
        URI uri = URI.create(urlString);
        String fragment = uri.getFragment();                // e.g. "/plant-list" or "/plant-list?id=42"
        if (fragment == null || fragment.isBlank()) {
            return Optional.empty();
        }

        // Drop any query part inside the fragment: "/plant-list?id=42" -> "/plant-list"
        int queryIndex = fragment.indexOf('?');
        String fragmentPathOnly = queryIndex >= 0 ? fragment.substring(0, queryIndex) : fragment;

        // Split on "/" and walk from the end to find the last non-empty segment
        String[] segments = fragmentPathOnly.split("/+");
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i];

            if (segment != null && !segment.isBlank()) {
                String decoded = URLDecoder.decode(segment, StandardCharsets.UTF_8);
                return Optional.of(decoded);
            }
        }

        return Optional.empty();
    }

    private Optional<Path> createTempDownloadDir() {
        try {
            Path temp = Files.createTempDirectory("solax_downloads");
            temp.toFile().deleteOnExit();
            log.trace("Created temp download directory: {}", temp.toAbsolutePath());
            return Optional.of(temp);
        } catch (IOException e) {
            log.error("Failed to create temporary directory for downloads: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Utility to log sleeps in trace level so long waits are visible in the logs.
     */
    private void traceSleep(long millis, String reason) throws InterruptedException {
        if (log.isTraceEnabled()) {
            log.trace("Sleeping {} ms ({})", millis, reason);
        }
        Thread.sleep(millis);
        if (log.isTraceEnabled()) {
            log.trace("Woke up after {} ms ({})", millis, reason);
        }
    }
}

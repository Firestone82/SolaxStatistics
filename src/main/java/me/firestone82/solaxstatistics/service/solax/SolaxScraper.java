package me.firestone82.solaxstatistics.service.solax;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxstatistics.model.StatisticsEntry;
import me.firestone82.solaxstatistics.scraper.BrowserSession;
import me.firestone82.solaxstatistics.scraper.SeleniumScraper;
import me.firestone82.solaxstatistics.configuration.solax.SolaxProperties;
import org.apache.poi.ss.usermodel.*;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
public class SolaxScraper extends SeleniumScraper {

    private static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration URL_STABLE_DURATION = Duration.ofSeconds(3);
    private static final Duration EXPORT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration EXPORT_POLL_INTERVAL = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final int DATE_RANGE_ATTEMPTS = 3;
    private static final int PLANT_LIST_ATTEMPTS = 3;
    private static final int TOTAL_STEPS = 5;

    private static final DateTimeFormatter EXPORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String PLANT_LIST_ROUTE = "#/plant-list";
    private static final String EXPORT_COMPLETED_STATUS = "Export completed";

    // Login page (user center)
    private static final By USERNAME_INPUT = By.cssSelector("#app .right-block .main-login input.arco-input[type='text']");
    private static final By PASSWORD_INPUT = By.cssSelector("#app .right-block .main-login input[type='password']");
    private static final By AGREEMENT_CHECKBOX = By.id("agreeMent");
    private static final By AGREEMENT_CHECKBOX_ICON = By.cssSelector("#agreeMent .arco-checkbox-icon-hover");
    private static final By AGREEMENT_CHECKBOX_INPUT = By.cssSelector("#agreeMent > input");
    private static final By LOGIN_BUTTON = By.cssSelector("#app .right-block .main-login .submit-button");

    // Privacy / terms confirmation dialog which can pop up over the application after login
    private static final By PRIVACY_DIALOG_CONFIRM_BUTTON = By.cssSelector(".privacy-dialog .arco-modal-footer button.arco-btn-primary");

    // Plant list page. Header buttons are matched by their icon, as hidden buttons shift the nth-child positions.
    private static final By EXPORT_RECORDS_BUTTON = By.xpath("//*[@id='container']//div[contains(@class,'header-right')]//button[.//i[contains(@class,'icon-export')]]");
    private static final By EXPORT_BUTTON = By.xpath("//*[@id='container']//div[contains(@class,'header-right')]//button[.//i[contains(@class,'icon-daochu')]]");

    // Export drawer. Drawers and modals are appended to <body>, so their position there is not stable.
    private static final By EXPORT_DRAWER = By.xpath("//div[contains(@class,'arco-drawer-container')]/div[contains(concat(' ', normalize-space(@class), ' '), ' arco-drawer ')][.//*[@id='time']]");
    private static final By EXPORT_DATE_INPUTS = By.cssSelector("#time .arco-picker-input > input");
    private static final By EXPORT_CONFIRM_BUTTON = By.cssSelector(".arco-drawer-footer button.arco-btn-primary");

    // Export records modal
    private static final By EXPORT_RECORDS_TABLE = By.cssSelector(".arco-modal-container .export-record-spin");
    private static final By EXPORT_RECORDS_LOADING = By.cssSelector(".arco-modal-container .export-record-spin.arco-spin-loading, .arco-modal-container .export-record-spin .arco-spin-loading");
    private static final By EXPORT_RECORD_ROWS = By.cssSelector(".arco-table-body tbody > tr.arco-table-tr:not(.arco-table-tr-empty)");
    private static final By EXPORT_RECORD_STATUS = By.cssSelector("td:nth-child(8)");
    private static final By EXPORT_RECORD_DOWNLOAD = By.cssSelector("td:nth-child(9) > span > span > span > div > div:nth-child(2)");

    private final String portalUrl;
    private final String username;
    private final String password;

    public SolaxScraper(SolaxProperties properties) {
        super("Solax", properties.isHeadless());
        this.portalUrl = properties.getUrl().getPortal();
        this.username = properties.getCredentials().getUsername();
        this.password = properties.getCredentials().getPassword();
    }

    public Optional<List<StatisticsEntry>> scrapeData(YearMonth yearMonth) {
        log.debug("Scraping Solax data for {}", yearMonth);

        return runInBrowser(browser -> {
            logStep(1, TOTAL_STEPS, "Logging in");
            String appBaseUrl = login(browser);

            logStep(2, TOTAL_STEPS, "Requesting export for %s", yearMonth);
            requestExport(browser, appBaseUrl, yearMonth);

            logStep(3, TOTAL_STEPS, "Waiting for export to complete");
            WebElement exportRecord = waitForCompletedExport(browser, appBaseUrl).orElse(null);
            if (exportRecord == null) {
                return Optional.empty();
            }

            logStep(4, TOTAL_STEPS, "Downloading the exported report");
            Path downloaded = downloadExport(browser, exportRecord).orElse(null);
            if (downloaded == null) {
                log.warn("No exported file was downloaded to {}", browser.getDownloadDir());
                return Optional.empty();
            }

            logStep(5, TOTAL_STEPS, "Parsing the exported report %s", downloaded.getFileName());
            return parseExport(downloaded, yearMonth);
        });
    }

    /**
     * Logs in through the user center and returns the base URL of the application it redirects to.
     */
    private String login(BrowserSession browser) throws InterruptedException {
        browser.navigate(portalUrl);
        browser.type(USERNAME_INPUT, username);
        browser.type(PASSWORD_INPUT, password);
        ensureAgreementChecked(browser);
        browser.click(LOGIN_BUTTON);

        // The user center redirects to the application, e.g. https://global.solaxcloud.com/blue/#/overview?stationId=...
        // Both the host and the path (blue, green, ...) differ between accounts, so the base URL is taken from the redirect.
        try {
            browser.waitUntil(driver -> isApplicationUrl(driver.getCurrentUrl()), LOGIN_TIMEOUT);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Login was not redirected to the application, still at " + BrowserSession.withoutQuery(browser.getCurrentUrl()) + ". Check the credentials.", e);
        }

        // The application keeps redirecting while it initializes (e.g. to '#/overview?stationId=...'),
        // any navigation done before it settles gets overridden by these redirects
        if (!browser.waitForStableUrl(URL_STABLE_DURATION, LOGIN_TIMEOUT)) {
            log.debug("Application URL did not settle within {} seconds, continuing anyway", LOGIN_TIMEOUT.toSeconds());
        }

        String currentUrl = browser.getCurrentUrl();
        String appBaseUrl = currentUrl.substring(0, currentUrl.indexOf('#'));
        log.debug("Logged in, application base URL: {}", appBaseUrl);

        return appBaseUrl;
    }

    private static boolean isApplicationUrl(String url) {
        // On the way to the overview, the application passes '#/login_sc?token=...&centerHost=.../user-center/'
        return url != null && url.contains("#/") && !url.contains("/user-center") && !url.contains("#/login");
    }

    private void ensureAgreementChecked(BrowserSession browser) {
        WebElement agreement = browser.waitForPresent(AGREEMENT_CHECKBOX);
        if (isChecked(agreement)) {
            log.trace("Agreement checkbox is already checked");
            return;
        }

        // Clicking the middle of the label hits the 'Privacy Policy' link and the <input> itself has zero size,
        // so the checkbox icon is clicked. Clicking it again would uncheck it, hence the state checks.
        browser.click(AGREEMENT_CHECKBOX_ICON);

        try {
            browser.waitUntil(driver -> isChecked(driver.findElement(AGREEMENT_CHECKBOX)), Duration.ofSeconds(2));
        } catch (TimeoutException e) {
            log.trace("Agreement checkbox still unchecked, clicking its input via JavaScript");
            browser.jsClick(browser.getDriver().findElement(AGREEMENT_CHECKBOX_INPUT));
            browser.waitUntil(driver -> isChecked(driver.findElement(AGREEMENT_CHECKBOX)));
        }

        log.trace("Agreement checkbox is checked");
    }

    private static boolean isChecked(WebElement checkbox) {
        String classes = checkbox.getDomAttribute("class");
        return classes != null && classes.contains("arco-checkbox-checked");
    }

    private void requestExport(BrowserSession browser, String appBaseUrl, YearMonth yearMonth) throws InterruptedException {
        LocalDate from = yearMonth.atDay(1);
        // Future days are disabled in the date picker, so the current month can only be exported up to today
        LocalDate to = Collections.min(List.of(yearMonth.atEndOfMonth(), LocalDate.now()));

        openPlantList(browser, appBaseUrl);
        browser.click(EXPORT_BUTTON);

        WebElement drawer = browser.waitForVisible(EXPORT_DRAWER);
        fillExportDateRange(browser, drawer, from, to);

        log.trace("Clicking export confirm button: {}", EXPORT_CONFIRM_BUTTON);
        browser.click(drawer.findElement(EXPORT_CONFIRM_BUTTON));

        try {
            browser.waitUntil(ExpectedConditions.invisibilityOfElementLocated(EXPORT_DRAWER));
        } catch (TimeoutException e) {
            throw new IllegalStateException("Export drawer did not close after confirming, the export was not requested", e);
        }

        log.debug("Export requested for {} - {}", from, to);
        browser.pause(2000, "allow the export task to be registered");
    }

    private void fillExportDateRange(BrowserSession browser, WebElement drawer, LocalDate from, LocalDate to) throws InterruptedException {
        String fromText = from.format(EXPORT_DATE_FORMATTER);
        String toText = to.format(EXPORT_DATE_FORMATTER);

        // The range picker swaps the dates when the typed start is after the current end (e.g. a range left from
        // a previous export), which leaves a wrong range behind. Typing both dates again fixes that.
        for (int attempt = 1; ; attempt++) {
            List<WebElement> inputs = browser.waitUntil(driver -> {
                List<WebElement> found = drawer.findElements(EXPORT_DATE_INPUTS);
                return found.size() >= 2 ? found : null;
            });

            String shownFrom = inputs.get(0).getDomProperty("value");
            String shownTo = inputs.get(1).getDomProperty("value");
            log.trace("Export date range shows '{}' - '{}', expecting '{}' - '{}'", shownFrom, shownTo, fromText, toText);

            if (fromText.equals(shownFrom) && toText.equals(shownTo)) {
                log.debug("Export date range set to {} - {}", fromText, toText);
                return;
            }

            if (attempt > DATE_RANGE_ATTEMPTS) {
                throw new IllegalStateException("Unable to set export date range to " + fromText + " - " + toText + ", date picker shows " + shownFrom + " - " + shownTo);
            }

            typeDate(browser, inputs.get(0), fromText);
            typeDate(browser, drawer.findElements(EXPORT_DATE_INPUTS).get(1), toText);
        }
    }

    private void typeDate(BrowserSession browser, WebElement input, String date) throws InterruptedException {
        log.trace("Typing date {} into date picker input", date);
        browser.click(input);

        // Select the current text so it is replaced by typing. Emptying the input first makes the picker restore the previous date.
        browser.executeScript("arguments[0].focus(); arguments[0].select();", input);
        new Actions(browser.getDriver())
                .sendKeys(date)
                .sendKeys(Keys.ENTER)
                .perform();

        browser.pause(300, "after typing date " + date);
    }

    private Optional<WebElement> waitForCompletedExport(BrowserSession browser, String appBaseUrl) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + EXPORT_TIMEOUT.toNanos();
        int poll = 0;

        while (true) {
            poll++;

            // Reload the page, so the export records are loaded again
            log.trace("Poll {}: reloading plant list", poll);
            openPlantList(browser, appBaseUrl);
            browser.click(EXPORT_RECORDS_BUTTON);

            Optional<WebElement> latestRecord = findLatestExportRecord(browser);
            if (latestRecord.isPresent()) {
                WebElement record = latestRecord.get();
                String status = record.findElement(EXPORT_RECORD_STATUS).getText().trim();
                log.debug("Poll {}: latest export record has status '{}' ({})", poll, status, record.getText().replace("\n", " | "));

                if (EXPORT_COMPLETED_STATUS.equalsIgnoreCase(status)) {
                    return latestRecord;
                }

                if (status.toLowerCase(Locale.ROOT).contains("fail")) {
                    log.warn("Export failed with status '{}'", status);
                    return Optional.empty();
                }
            } else {
                log.debug("Poll {}: there are no export records yet", poll);
            }

            if (System.nanoTime() >= deadlineNanos) {
                log.warn("Export did not complete within {} seconds", EXPORT_TIMEOUT.toSeconds());
                return Optional.empty();
            }

            browser.pause(EXPORT_POLL_INTERVAL.toMillis(), "before polling export status again");
        }
    }

    private Optional<WebElement> findLatestExportRecord(BrowserSession browser) throws InterruptedException {
        WebElement table = browser.waitForVisible(EXPORT_RECORDS_TABLE);

        browser.pause(500, "allow export records to start loading");
        browser.waitUntil(driver -> driver.findElements(EXPORT_RECORDS_LOADING).isEmpty());

        return table.findElements(EXPORT_RECORD_ROWS).stream().findFirst();
    }

    private Optional<Path> downloadExport(BrowserSession browser, WebElement exportRecord) {
        Set<Path> existingFiles = browser.listDownloads();

        log.trace("Clicking download button: {}", EXPORT_RECORD_DOWNLOAD);
        browser.click(browser.waitForClickable(exportRecord.findElement(EXPORT_RECORD_DOWNLOAD)));

        return browser.waitForNewDownload(existingFiles, DOWNLOAD_TIMEOUT);
    }

    /**
     * Parses the exported report and checks it contains data of the requested month.
     */
    private Optional<List<StatisticsEntry>> parseExport(Path downloaded, YearMonth yearMonth) {
        List<StatisticsEntry> entries = parseExcel(downloaded);
        if (entries.isEmpty()) {
            log.warn("No entries parsed from {}", downloaded.getFileName());
            return Optional.empty();
        }

        long entriesInMonth = entries.stream().filter(entry -> YearMonth.from(entry.getDateTime()).equals(yearMonth)).count();
        if (entriesInMonth == 0) {
            log.error("Downloaded export {} contains no data for {}, probably an older export was downloaded", downloaded.getFileName(), yearMonth);
            return Optional.empty();
        } else if (entriesInMonth < entries.size()) {
            log.warn("Downloaded export {} contains {} entries outside of {}", downloaded.getFileName(), entries.size() - entriesInMonth, yearMonth);
        }

        log.info("Scraped {} Solax entries for {}", entries.size(), yearMonth);
        return Optional.of(entries);
    }

    /**
     * Opens (or reloads, when already there) the plant list. Retried, as the application can redirect to the overview while loading.
     */
    private void openPlantList(BrowserSession browser, String appBaseUrl) throws InterruptedException {
        String plantListUrl = appBaseUrl + PLANT_LIST_ROUTE;

        for (int attempt = 1; attempt <= PLANT_LIST_ATTEMPTS; attempt++) {
            if (browser.getCurrentUrl().contains(PLANT_LIST_ROUTE)) {
                browser.reload();
            } else {
                browser.navigate(plantListUrl);
            }

            if (waitForPlantList(browser)) {
                return;
            }

            log.debug("Application redirected from the plant list to {}, opening it again", BrowserSession.withoutQuery(browser.getCurrentUrl()));
            browser.pause(2000, "allow application to settle before opening plant list again");
        }

        throw new IllegalStateException("Unable to open the plant list at " + plantListUrl);
    }

    /**
     * Waits for the plant list to be usable, returns false when the application redirected elsewhere instead.
     */
    private boolean waitForPlantList(BrowserSession browser) {
        log.trace("Waiting for plant list, export records button: {}", EXPORT_RECORDS_BUTTON);
        Optional<WebElement> exportRecordsButton = browser.waitUntil(driver -> {
            String url = driver.getCurrentUrl();
            if (url == null || !url.contains(PLANT_LIST_ROUTE)) {
                return Optional.empty();
            }

            WebElement button = ExpectedConditions.elementToBeClickable(EXPORT_RECORDS_BUTTON).apply(driver);
            return button != null ? Optional.of(button) : null;
        });

        if (exportRecordsButton.isEmpty()) {
            return false;
        }

        acceptPrivacyDialogIfShown(browser);
        return true;
    }

    private void acceptPrivacyDialogIfShown(BrowserSession browser) {
        browser.findDisplayed(PRIVACY_DIALOG_CONFIRM_BUTTON).ifPresent(button -> {
            log.info("Privacy confirmation dialog is shown, accepting it");
            browser.click(button);
        });
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
}

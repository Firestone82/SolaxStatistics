package me.firestone82.solaxstatistics.service.cez;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxstatistics.configuration.cez.CEZProperties;
import me.firestone82.solaxstatistics.model.EnergyEntry;
import me.firestone82.solaxstatistics.scraper.BrowserSession;
import me.firestone82.solaxstatistics.scraper.SeleniumScraper;
import me.firestone82.solaxstatistics.utils.NumberUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
public class CEZScraper extends SeleniumScraper {

    private static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration URL_STABLE_DURATION = Duration.ofSeconds(3);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final int TOTAL_STEPS = 4;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final Charset EXPORT_CHARSET = Charset.forName("ISO-8859-2");

    private static final String CAS_LOGIN_PATH = "/cas/login";
    // Paths of the OAuth handshake between the portal and the login, the portal session only exists after them
    private static final String OAUTH_PATH = "/oauth2/";

    // ČEZ login (CAS on mepas.cez.cz), the form is hidden until the page has fully loaded
    private static final By USERNAME_INPUT = By.id("username");
    private static final By PASSWORD_INPUT = By.id("password");
    private static final By LOGIN_BUTTON = By.cssSelector("#fm1 button.loginBtn[type='submit']");
    private static final By LOGIN_ERROR_ALERT = By.cssSelector(".alert-danger");

    // Cookiebot consent banner of the portal
    private static final By COOKIE_DECLINE_BUTTON = By.id("CybotCookiebotDialogBodyButtonDecline");

    private final String portalUrl;
    private final String portalHost;
    private final String exportUrl;
    private final long meterId;
    private final String username;
    private final String password;

    public CEZScraper(CEZProperties properties) {
        super("CEZ", properties.isHeadless());
        this.portalUrl = properties.getUrl().getPortal();
        this.portalHost = URI.create(portalUrl).getHost();
        this.exportUrl = properties.getUrl().getExport();
        this.meterId = properties.getMeterId();
        this.username = properties.getCredentials().getUsername();
        this.password = properties.getCredentials().getPassword();
    }

    public Optional<List<EnergyEntry>> scrapeData(YearMonth yearMonth) {
        log.debug("Scraping CEZ data for {} (electrometer {})", yearMonth, meterId);

        return runInBrowser(browser -> {
            logStep(1, TOTAL_STEPS, "Logging in");
            login(browser);

            logStep(2, TOTAL_STEPS, "Dismissing cookie banner");
            dismissCookieBanner(browser);

            logStep(3, TOTAL_STEPS, "Downloading export for %s", yearMonth);
            Path downloaded = downloadExport(browser, yearMonth);

            logStep(4, TOTAL_STEPS, "Parsing the export %s", downloaded.getFileName());
            return parseExport(downloaded, yearMonth);
        });
    }

    /**
     * Opens the portal, which redirects to the ČEZ login (every run starts with a fresh browser, so without a session),
     * and logs in there. Afterwards the browser is back on the portal with an authenticated session.
     */
    private void login(BrowserSession browser) throws InterruptedException {
        browser.navigate(portalUrl);

        // An outage or maintenance page would stay on the portal host, so only the login form counts as loaded
        try {
            browser.waitUntil(driver -> browser.findDisplayed(USERNAME_INPUT).isPresent());
        } catch (TimeoutException e) {
            throw new IllegalStateException("CEZ login page did not load, browser is at %s (page title '%s')".formatted(
                    BrowserSession.withoutQuery(browser.getCurrentUrl()), browser.getDriver().getTitle()), e);
        }

        browser.type(USERNAME_INPUT, username);
        browser.type(PASSWORD_INPUT, password);
        browser.click(LOGIN_BUTTON);

        // Wrong credentials keep the browser on the login page with an alert, so fail on it instead of waiting for the timeout
        Optional<String> loginError;
        try {
            loginError = browser.waitUntil(driver -> {
                Optional<WebElement> alert = browser.findDisplayed(LOGIN_ERROR_ALERT);
                if (alert.isPresent()) {
                    return Optional.of(alert.get().getText().trim());
                }

                return isPortalUrl(driver.getCurrentUrl()) ? Optional.<String>empty() : null;
            }, LOGIN_TIMEOUT);
        } catch (TimeoutException e) {
            throw new IllegalStateException("CEZ login did not redirect back to the portal within %d seconds, still at %s".formatted(
                    LOGIN_TIMEOUT.toSeconds(), BrowserSession.withoutQuery(browser.getCurrentUrl())), e);
        }

        if (loginError.isPresent()) {
            throw new IllegalStateException("CEZ login failed: " + loginError.get());
        }

        if (!browser.waitForStableUrl(URL_STABLE_DURATION, LOGIN_TIMEOUT)) {
            log.debug("Portal URL did not settle within {} seconds, continuing anyway", LOGIN_TIMEOUT.toSeconds());
        }

        log.debug("Logged in, portal is at {}", BrowserSession.withoutQuery(browser.getCurrentUrl()));
    }

    /**
     * Declines the cookie banner when it is already shown. Best effort without waiting, as the banner does not block
     * the export download, it would only cover the page in error screenshots.
     */
    private void dismissCookieBanner(BrowserSession browser) {
        try {
            browser.findDisplayed(COOKIE_DECLINE_BUTTON).ifPresentOrElse(button -> {
                log.debug("Cookie banner is shown, declining it");
                browser.click(button);
            }, () -> log.trace("No cookie banner is shown"));
        } catch (WebDriverException e) {
            log.debug("Unable to dismiss cookie banner: {}", e.getMessage());
        }
    }

    /**
     * Downloads the CSV export of the whole month for the electrometer.
     */
    private Path downloadExport(BrowserSession browser, YearMonth yearMonth) throws IOException {
        String targetUrl = buildExportUrl(yearMonth);
        log.debug("Downloading CEZ export for {} (electrometer {}) from {}", yearMonth, meterId, BrowserSession.withoutQuery(targetUrl));

        Set<Path> existingFiles = browser.listDownloads();
        // The export is served as a file download, so the browser stays on the current page
        browser.navigate(targetUrl);

        // Without a valid session the export redirects to the login instead
        if (isCasLoginUrl(browser.getCurrentUrl())) {
            throw new IllegalStateException("CEZ export redirected to the login page, the session is not authenticated");
        }

        Path downloaded = browser.waitForNewDownload(existingFiles, DOWNLOAD_TIMEOUT).orElse(null);
        if (downloaded == null) {
            String currentUrl = browser.getCurrentUrl();
            throw new IllegalStateException("No CEZ export was downloaded within %d seconds, browser is at %s%s".formatted(
                    DOWNLOAD_TIMEOUT.toSeconds(), BrowserSession.withoutQuery(currentUrl),
                    isCasLoginUrl(currentUrl) ? " (login page, the session is not authenticated)" : ""));
        }

        // An error page served as a download would otherwise only show up as rows which fail to parse
        if (isHtmlPage(downloaded)) {
            deleteDownload(downloaded);
            throw new IllegalStateException("Downloaded CEZ export %s is an HTML page instead of a CSV".formatted(downloaded.getFileName()));
        }

        log.debug("Downloaded CEZ export {} ({} B)", downloaded.getFileName(), Files.size(downloaded));
        return downloaded;
    }

    private String buildExportUrl(YearMonth yearMonth) {
        String from = URLEncoder.encode(yearMonth.atDay(1).format(DATE_FORMATTER), StandardCharsets.UTF_8);
        String to = URLEncoder.encode(yearMonth.plusMonths(1).atDay(1).format(DATE_FORMATTER), StandardCharsets.UTF_8);

        return exportUrl + "?format=csv-simple&idAssembly=-1003&intervalFrom=" + from + "%2000%3A00&intervalTo=" + to + "%2000%3A00&electrometerId=" + meterId;
    }

    /**
     * Parses the downloaded export (deleting it afterwards) and checks it contains data of the requested month,
     * so the caller does not cache a wrong export.
     */
    private Optional<List<EnergyEntry>> parseExport(Path downloaded, YearMonth yearMonth) throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(downloaded, EXPORT_CHARSET);
        } finally {
            deleteDownload(downloaded);
        }

        List<EnergyEntry> entries = new ArrayList<>();

        // First line is the header
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;

            try {
                entries.add(parseRow(line));
            } catch (Exception e) {
                log.debug("Skipping invalid row {}: '{}' ({})", i + 1, line, e.getMessage());
            }
        }

        if (entries.isEmpty()) {
            log.warn("No entries parsed from CEZ export {} ({} lines)", downloaded.getFileName(), lines.size());
            return Optional.empty();
        }

        // Timestamps mark the end of the 15-minute interval, so the interval start decides the month
        long entriesInMonth = entries.stream()
                .filter(entry -> YearMonth.from(entry.getDateTime().minusMinutes(15)).equals(yearMonth))
                .count();

        if (entriesInMonth == 0) {
            log.error("CEZ export {} contains no data for {}", downloaded.getFileName(), yearMonth);
            return Optional.empty();
        } else if (entriesInMonth < entries.size()) {
            log.debug("CEZ export {} contains {} entries outside of {}", downloaded.getFileName(), entries.size() - entriesInMonth, yearMonth);
        }

        log.info("Scraped {} CEZ entries for {}", entries.size(), yearMonth);
        return Optional.of(entries);
    }

    /**
     * Parses a row of the export: end of the interval, import and export. Throws when the row is not a valid data row.
     */
    private static EnergyEntry parseRow(String line) {
        String[] parts = line.split(";");

        // Remove quotes and trim whitespace
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].replace("\"", "").trim();
        }

        LocalDateTime dateTime = LocalDateTime.parse(parts[0], DATE_TIME_FORMATTER);
        double importValue = parseValue(parts[1]);
        double exportValue = parseValue(parts[3]);

        return new EnergyEntry(dateTime, importValue, exportValue);
    }

    private static double parseValue(String text) {
        // Checked here, as NumberUtils logs an error for an empty value and the row is skipped anyway
        if (text.isEmpty()) {
            throw new IllegalArgumentException("missing value");
        }

        Double value = NumberUtils.parseNumber(text);
        if (value == null) {
            throw new IllegalArgumentException("invalid number '" + text + "'");
        }

        return value;
    }

    private boolean isPortalUrl(String url) {
        if (url == null || url.contains(OAUTH_PATH)) {
            return false;
        }

        try {
            return portalHost.equalsIgnoreCase(URI.create(url).getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isCasLoginUrl(String url) {
        return url != null && url.contains(CAS_LOGIN_PATH);
    }

    private static boolean isHtmlPage(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(1024);
        }

        String text = new String(head, EXPORT_CHARSET).stripLeading().toLowerCase(Locale.ROOT);
        return text.startsWith("<") || text.contains("<html");
    }

    private static void deleteDownload(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Unable to delete downloaded export {}: {}", file.getFileName(), e.getMessage());
        }
    }
}

package me.firestone82.solaxstatistics.scraper;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Chrome browser driven by Selenium, with the waiting, clicking and download helpers shared by all scrapers.
 */
@Slf4j
public class BrowserSession implements AutoCloseable {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    @Getter
    private final WebDriver driver;

    @Getter
    private final Path downloadDir;

    private final WebDriverWait wait;

    private BrowserSession(WebDriver driver, Path downloadDir) {
        this.driver = driver;
        this.downloadDir = downloadDir;
        this.wait = createWait(driver, DEFAULT_TIMEOUT);
    }

    /**
     * Starts a new Chrome instance which downloads files into the given directory.
     */
    public static BrowserSession open(Path downloadDir, boolean headless) {
        ChromeOptions options = getChromeOptions(headless);

        Map<String, Object> chromePrefs = Map.of(
                "download.default_directory", downloadDir.toFile().getAbsolutePath(),
                "download.prompt_for_download", false,
                "download.directory_upgrade", true,
                "safebrowsing.enabled", true,
                "intl.accept_languages", "en-US,en"
        );
        options.setExperimentalOption("prefs", chromePrefs);
        log.trace("Starting Chrome (headless={}) with prefs: {}", headless, chromePrefs);

        return new BrowserSession(new ChromeDriver(options), downloadDir);
    }

    private static @NonNull ChromeOptions getChromeOptions(boolean headless) {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--no-sandbox", "--disable-dev-shm-usage");

        if (headless) {
            options.addArguments("--headless=new", "--disable-gpu");
        }

        // Fixed desktop size, so the layout (and the selectors) are the same with or without headless mode
        options.addArguments("--window-size=1920,1080");

        // Texts matched by the scrapers (e.g. export statuses) are in English
        options.addArguments("--lang=en-US");

        return options;
    }

    // ---------------------------------------------------------------- Navigation

    public void navigate(String url) {
        log.trace("Navigating to {}", withoutQuery(url));
        driver.get(url);
    }

    public void reload() {
        log.trace("Reloading {}", withoutQuery(getCurrentUrl()));
        driver.navigate().refresh();
    }

    /**
     * Current URL of the browser, or a placeholder when the browser does not respond anymore.
     */
    public String getCurrentUrl() {
        try {
            String url = driver.getCurrentUrl();
            return url != null ? url : "unknown page";
        } catch (Exception e) {
            return "unknown page";
        }
    }

    /**
     * Strips the query from a URL for logging, as login redirects carry tokens and codes in it.
     */
    public static String withoutQuery(String url) {
        int queryIndex = url.indexOf('?');
        return queryIndex >= 0 ? url.substring(0, queryIndex) : url;
    }

    // ---------------------------------------------------------------- Waiting

    /**
     * Waits (with the default timeout) until the condition returns a non-null value other than {@code false}.
     */
    public <T> T waitUntil(Function<? super WebDriver, T> condition) {
        return wait.until(condition);
    }

    public <T> T waitUntil(Function<? super WebDriver, T> condition, Duration timeout) {
        return createWait(driver, timeout).until(condition);
    }

    public WebElement waitForPresent(By locator) {
        log.trace("Waiting for element to be present: {}", locator);
        return wait.until(ExpectedConditions.presenceOfElementLocated(locator));
    }

    public WebElement waitForVisible(By locator) {
        log.trace("Waiting for element to be visible: {}", locator);
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    public WebElement waitForClickable(By locator) {
        log.trace("Waiting for element to be clickable: {}", locator);
        return wait.until(ExpectedConditions.elementToBeClickable(locator));
    }

    public WebElement waitForClickable(WebElement element) {
        return wait.until(ExpectedConditions.elementToBeClickable(element));
    }

    /**
     * Waits until the URL has not changed for the given time, e.g. while a single page application keeps redirecting.
     *
     * @return false when the URL did not settle within the timeout
     */
    public boolean waitForStableUrl(Duration stableFor, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        String lastUrl = getCurrentUrl();
        long stableSinceNanos = System.nanoTime();

        while (System.nanoTime() < deadlineNanos) {
            Thread.sleep(500);
            String url = getCurrentUrl();

            if (!Objects.equals(url, lastUrl)) {
                log.trace("Redirected to {}", withoutQuery(url));
                lastUrl = url;
                stableSinceNanos = System.nanoTime();
            } else if (System.nanoTime() - stableSinceNanos >= stableFor.toNanos()) {
                log.trace("URL settled at {}", withoutQuery(url));
                return true;
            }
        }

        return false;
    }

    /**
     * Sleeps for the given time, logging the reason in trace level so long waits are visible in the logs.
     */
    public void pause(long millis, String reason) throws InterruptedException {
        log.trace("Sleeping {} ms ({})", millis, reason);
        Thread.sleep(millis);
    }

    // ---------------------------------------------------------------- Interaction

    /**
     * First displayed element matching the locator, without waiting for it.
     */
    public Optional<WebElement> findDisplayed(By locator) {
        for (WebElement element : driver.findElements(locator)) {
            try {
                if (element.isDisplayed()) {
                    return Optional.of(element);
                }
            } catch (StaleElementReferenceException ignored) {
                // Element was re-rendered meanwhile, check the next one
            }
        }

        return Optional.empty();
    }

    public void click(By locator) {
        log.trace("Clicking: {}", locator);
        click(waitForClickable(locator));
    }

    /**
     * Clicks the element, falling back to a JavaScript click when something (e.g. a popup) covers it.
     */
    public void click(WebElement element) {
        try {
            element.click();
        } catch (ElementNotInteractableException e) {
            log.trace("Native click failed ({}), falling back to JavaScript click", e.getClass().getSimpleName());
            jsClick(element);
        }
    }

    public void jsClick(WebElement element) {
        executeScript("arguments[0].click();", element);
    }

    /**
     * Types the text into the input once it is clickable. The text itself is not logged, as it can be a password.
     */
    public void type(By locator, String text) {
        log.trace("Typing into: {}", locator);
        waitForClickable(locator).sendKeys(text);
    }

    public Object executeScript(String script, Object... args) {
        return ((JavascriptExecutor) driver).executeScript(script, args);
    }

    // ---------------------------------------------------------------- Downloads

    public Set<Path> listDownloads() {
        try (Stream<Path> stream = Files.list(downloadDir)) {
            return stream.collect(Collectors.toSet());
        } catch (IOException e) {
            log.trace("IOException while listing downloads: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * Waits for a file, which is not in {@code existingFiles}, to be completely downloaded.
     */
    public Optional<Path> waitForNewDownload(Set<Path> existingFiles, Duration timeout) {
        log.trace("Waiting for a new download in '{}' (timeout {}s)", downloadDir.toAbsolutePath(), timeout.toSeconds());
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Map<Path, Long> previousSizes = new HashMap<>();

        while (System.nanoTime() < deadlineNanos) {
            for (Path file : listDownloads()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (existingFiles.contains(file) || name.endsWith(".crdownload") || name.endsWith(".tmp") || name.startsWith(".")) {
                    continue;
                }

                long size = file.toFile().length();
                log.trace("Download candidate: name='{}', size={}B", file.getFileName(), size);

                // Wait until the size stops changing, so the file is completely written
                if (size > 0 && Objects.equals(previousSizes.put(file, size), size)) {
                    log.debug("Detected completed download: {}", file.getFileName());
                    return Optional.of(file);
                }
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for download: {}", e.getMessage(), e);
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.debug("No completed download found before timeout");
        return Optional.empty();
    }

    // ---------------------------------------------------------------- Diagnostics

    /**
     * Saves a screenshot of the current page into the download directory.
     */
    public Optional<Path> saveScreenshot(String prefix) {
        try {
            File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            Path target = downloadDir.resolve("%s-%d.png".formatted(prefix, System.currentTimeMillis()));
            Files.copy(screenshot.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            log.error("Saved screenshot of the page to {}", target.toAbsolutePath());
            return Optional.of(target);
        } catch (Exception e) {
            log.debug("Unable to save screenshot of the page: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        try {
            log.trace("Quitting WebDriver");
            driver.quit();
        } catch (Exception e) {
            log.trace("Ignoring exception during WebDriver quit: {}", e.getMessage());
        }
    }

    private static WebDriverWait createWait(WebDriver driver, Duration timeout) {
        WebDriverWait wait = new WebDriverWait(driver, timeout);
        wait.ignoring(StaleElementReferenceException.class);
        return wait;
    }
}

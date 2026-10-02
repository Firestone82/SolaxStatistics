package me.firestone82.solaxstatistics.scraper;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxstatistics.utils.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Base of the scrapers which drive a browser. Takes care of the download directory and the browser lifecycle,
 * so the scrapers only implement their steps on top of {@link BrowserSession}.
 */
@Slf4j
public abstract class SeleniumScraper {

    private final String name;
    private final boolean headless;

    /**
     * Directory for the downloaded files, a temporary directory is used when not set.
     */
    @Setter
    private File downloadDir;

    protected SeleniumScraper(String name, boolean headless) {
        this.name = name;
        this.headless = headless;
    }

    /**
     * Opens a new browser, runs the scraping steps in it and closes it again. When a step fails, the error is logged
     * together with the page it happened on, and a screenshot of that page is saved to the download directory.
     */
    protected <T> Optional<T> runInBrowser(BrowserTask<T> task) {
        Path dir = prepareDownloadDir().orElse(null);
        if (dir == null) {
            return Optional.empty();
        }

        BrowserSession browser;
        try {
            browser = BrowserSession.open(dir, headless);
            log.trace("{}: browser started (headless={}), downloads go to {}", name, headless, dir.toAbsolutePath());
        } catch (Exception e) {
            log.error("{}: failed to start the browser: {}", name, e.getMessage(), e);
            return Optional.empty();
        }

        long startNanos = System.nanoTime();
        try {
            Optional<T> result = task.run(browser);
            log.debug("{}: scraping finished in {} ms", name, (System.nanoTime() - startNanos) / 1_000_000);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{}: scraping interrupted", name);
            return Optional.empty();
        } catch (Exception e) {
            log.error("{}: scraping failed at {}: {}", name, BrowserSession.withoutQuery(browser.getCurrentUrl()), e.getMessage(), e);
            browser.saveScreenshot(name.toLowerCase(Locale.ROOT) + "-error");
            return Optional.empty();
        } finally {
            browser.close();
        }
    }

    /**
     * Logs the start of a scraping step, so the logs show how far the scraper got.
     */
    protected void logStep(int step, int totalSteps, String description, Object... args) {
        if (log.isDebugEnabled()) {
            log.debug("{}: step {}/{}: {}", name, step, totalSteps, description.formatted(args));
        }
    }

    private Optional<Path> prepareDownloadDir() {
        if (downloadDir == null) {
            log.trace("{}: no download directory set, creating a temporary one", name);
            return FileUtils.createTempFolder(name.toLowerCase(Locale.ROOT) + "_downloads");
        }

        try {
            return Optional.of(Files.createDirectories(downloadDir.toPath()));
        } catch (IOException e) {
            log.error("{}: failed to create download directory {}: {}", name, downloadDir, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @FunctionalInterface
    protected interface BrowserTask<T> {
        Optional<T> run(BrowserSession browser) throws Exception;
    }
}

package me.firestone82.solaxstatistics.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class FileUtils {
    // Monthly data files, e.g. prices_2025-09.csv or summary_2025-09.json
    private static final Pattern MONTH_FILE = Pattern.compile(".+_(\\d{4})-\\d{2}\\.\\w+");

    /**
     * File holding data of the month, placed in the folder of its year (e.g. data/ote/2025/prices_2025-09.csv).
     */
    public static File getMonthFile(File dataDir, YearMonth yearMonth, String fileName) {
        File yearDir = ensureFolderCreated(dataDir.getPath(), String.valueOf(yearMonth.getYear()));
        return new File(yearDir, fileName);
    }

    /**
     * Moves monthly data files stored directly in the directory (as older versions did) into the folders of their years.
     */
    public static void moveMonthFilesToYearFolders(File dataDir) {
        File[] files = dataDir.listFiles(file -> file.isFile() && MONTH_FILE.matcher(file.getName()).matches());
        if (files == null) {
            return;
        }

        for (File file : files) {
            Matcher matcher = MONTH_FILE.matcher(file.getName());
            if (!matcher.matches()) {
                continue;
            }

            File yearDir = ensureFolderCreated(dataDir.getPath(), matcher.group(1));
            Path target = new File(yearDir, file.getName()).toPath();

            if (Files.exists(target)) {
                log.warn("Not moving {} into its year folder, {} already exists", file.getPath(), target);
                continue;
            }

            try {
                Files.move(file.toPath(), target);
                log.info("Moved {} into its year folder: {}", file.getPath(), target);
            } catch (IOException e) {
                log.error("Failed to move {} into its year folder: {}", file.getPath(), e.getMessage(), e);
            }
        }
    }

    public static File ensureFolderCreated(String parentPath, String path) {
        File folder = new File(parentPath, path);
        if (!folder.exists()) {
            boolean ignored = folder.mkdirs();
        }

        return folder;
    }

    public static File ensureFileCreated(String parentPath, String fileName) {
        File file = new File(parentPath, fileName);

        if (file.getParent() != null) {
            boolean ignored = file.getParentFile().mkdirs();
        }

        try {
            if (!file.exists()) {
                boolean ignored = file.createNewFile();
            }
        } catch (IOException e) {
            log.error("Failed to file '{}' in directory '{}'", fileName, parentPath, e);
        }

        return file;
    }

    public static Optional<Path> createTempFolder(String folder) {
        try {
            Path temp = Files.createTempDirectory(folder);
            temp.toFile().deleteOnExit();

            return Optional.of(temp);
        } catch (IOException e) {
            log.error("Failed to create temporary directory for downloads: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }
}

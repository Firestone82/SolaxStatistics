package me.firestone82.solaxstatistics.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Slf4j
public class FileUtils {

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

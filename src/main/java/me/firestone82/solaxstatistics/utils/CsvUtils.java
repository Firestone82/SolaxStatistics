package me.firestone82.solaxstatistics.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Slf4j
public class CsvUtils {
    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static <T> void saveToCsv(List<T> data, @NotNull File file) {
        log.debug("Saving {} records to CSV file: {}", data.size(), file.getPath());

        if (data.isEmpty()) {
            log.warn("Tried to save empty data list to CSV, skipping file creation.");
            return;
        }

        CSVFormat csvFormat = CSVFormat.Builder.create()
                .setHeader(getFieldNames(data.getFirst().getClass()))
                .get();

        try (
                Writer writer = Files.newBufferedWriter(file.toPath());
                CSVPrinter printer = new CSVPrinter(writer, csvFormat)
        ) {
            for (T item : data) {
                List<String> values = new ArrayList<>();

                for (Field field : item.getClass().getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(item);

                    if (value instanceof LocalDateTime dt) {
                        values.add(dt.format(DEFAULT_FORMATTER));
                    } else {
                        values.add(value != null ? value.toString() : "");
                    }
                }

                printer.printRecord(values);
            }

            log.info("Successfully saved {} records to CSV file: {}", data.size(), file.getPath());
        } catch (IOException | IllegalAccessException e) {
            log.error("Failed to write CSV file {}: {}", file.getPath(), e.getMessage(), e);
        }
    }


    public static <T> Optional<List<T>> loadFromCsv(File file, Class<T> clazz) {
        log.debug("Loading records of type '{}' from CSV file: {}", clazz.getSimpleName(), file.getPath());

        CSVFormat csvFormat = CSVFormat.Builder.create()
                .setHeader()
                .setSkipHeaderRecord(true)
                .get();

        List<T> result = new ArrayList<>();

        try (
                Reader reader = Files.newBufferedReader(file.toPath());
                CSVParser parser = CSVParser.builder()
                        .setReader(reader)
                        .setFormat(csvFormat)
                        .get()
        ) {
            for (CSVRecord record : parser) {
                T instance = clazz.getDeclaredConstructor().newInstance();

                for (Field field : clazz.getDeclaredFields()) {
                    field.setAccessible(true);
                    String value = record.get(field.getName());
                    Object converted = convertValue(field.getType(), value);
                    field.set(instance, converted);
                }

                result.add(instance);
            }

            log.info("Successfully loaded {} records from CSV file: {}", result.size(), file.getPath());
        } catch (Exception e) {
            log.error("Failed to read CSV file {}: {}", file.getPath(), e.getMessage(), e);
            return Optional.empty();
        }

        return Optional.of(result);
    }

    private static String[] getFieldNames(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredFields())
                .map(Field::getName)
                .toArray(String[]::new);
    }

    private static Object convertValue(Class<?> type, String value) {
        if (type == String.class) {
            return value;
        }

        if (type == int.class || type == Integer.class) {
            return Integer.parseInt(value);
        }

        if (type == long.class || type == Long.class) {
            return Long.parseLong(value);
        }

        if (type == double.class || type == Double.class) {
            return Double.parseDouble(value.replace(",", "."));
        }

        if (type == boolean.class || type == Boolean.class) {
            return Boolean.parseBoolean(value);
        }

        if (type == LocalDateTime.class) {
            return LocalDateTime.parse(value.trim(), DEFAULT_FORMATTER);
        }

        return null;
    }
}

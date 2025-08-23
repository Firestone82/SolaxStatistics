package me.firestone82.solaxstatistics.utils;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

@Slf4j
public class NumberUtils {

    public static Double parseNumber(String text) {
        return parseNumber(text, Double.class);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Number> T parseNumber(String text, Class<T> type) {
        try {
            String numeric = text.trim()
                    .replaceAll("[^\\d,.-]", "") // keep digits, minus, dot, comma
                    .replace(",", "."); // normalize comma to dot

            if (type == Integer.class) {
                return (T) Integer.valueOf((int) Double.parseDouble(numeric));
            } else if (type == Long.class) {
                return (T) Long.valueOf((long) Double.parseDouble(numeric));
            } else if (type == Double.class) {
                return (T) Double.valueOf(Double.parseDouble(numeric));
            } else if (type == Float.class) {
                return (T) Float.valueOf(Float.parseFloat(numeric));
            } else if (type == BigDecimal.class) {
                return (T) new BigDecimal(numeric);
            } else if (type == Short.class) {
                return (T) Short.valueOf((short) Double.parseDouble(numeric));
            } else if (type == Byte.class) {
                return (T) Byte.valueOf((byte) Double.parseDouble(numeric));
            } else {
                throw new IllegalArgumentException("Unsupported number type: " + type);
            }
        } catch (Exception e) {
            log.error("Failed to parse number from text: '{}'", text, e);
            return null;
        }
    }
}

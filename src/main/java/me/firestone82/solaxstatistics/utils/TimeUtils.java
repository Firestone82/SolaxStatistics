package me.firestone82.solaxstatistics.utils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public class TimeUtils {

    /**
     * Start of the quarter-hour the time falls into, e.g. 10:44:59 -> 10:30.
     */
    public static LocalDateTime toQuarterStart(LocalDateTime dateTime) {
        return dateTime.truncatedTo(ChronoUnit.HOURS).plusMinutes(dateTime.getMinute() / 15 * 15);
    }
}

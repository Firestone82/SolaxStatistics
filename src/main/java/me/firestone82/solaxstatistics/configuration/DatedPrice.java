package me.firestone82.solaxstatistics.configuration;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.YearMonth;

/**
 * Price which applies from the month {@code from} until the next dated price of the same list takes over.
 */
@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class DatedPrice extends Price {
    private YearMonth from;
}

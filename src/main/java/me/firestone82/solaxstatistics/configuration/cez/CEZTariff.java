package me.firestone82.solaxstatistics.configuration.cez;

import lombok.Data;
import me.firestone82.solaxstatistics.configuration.DatedPrice;
import me.firestone82.solaxstatistics.configuration.Price;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Data
@ConfigurationProperties(prefix = "cez.tariff")
public class CEZTariff implements InitializingBean {
    private Price importPrice;

    /**
     * Fees deducted from the spot price of every exported kWh. Each one applies from its month until the next one
     * takes over, so a change of the contract does not alter the months before it.
     */
    private List<DatedPrice> exportFees = new ArrayList<>();

    /**
     * Fails on startup instead of computing the revenue with a wrong or missing fee.
     */
    @Override
    public void afterPropertiesSet() {
        if (exportFees.isEmpty()) {
            throw new IllegalStateException("No export fee is configured, cez.tariff.export-fees needs at least one entry");
        }

        if (exportFees.stream().map(DatedPrice::getFrom).anyMatch(Objects::isNull)) {
            throw new IllegalStateException("Every cez.tariff.export-fees entry needs the month it applies from (from: yyyy-MM)");
        }

        if (exportFees.stream().map(DatedPrice::getFrom).distinct().count() < exportFees.size()) {
            throw new IllegalStateException("Two cez.tariff.export-fees entries apply from the same month");
        }
    }

    /**
     * Export fee in force in the month, the one with the latest {@code from} which is not after it.
     */
    public Price getExportFee(YearMonth month) {
        return exportFees.stream()
                .filter(fee -> !fee.getFrom().isAfter(month))
                .max(Comparator.comparing(DatedPrice::getFrom))
                .orElseThrow(() -> new IllegalStateException(
                        "No export fee applies to %s, the first cez.tariff.export-fees entry starts later".formatted(month)));
    }
}

package me.firestone82.solaxstatistics.model.summary;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Getter
@AllArgsConstructor
public class SummaryRow {
    private LocalDateTime date;
    private double importCEZ;
    private double importRest;
    private double exportCEZ;
    private double exportRest;
    private double consumption;
    private double yield;
    private double priceEUR;
    private double priceCZK;
    private double importCostEUR;
    private double importCostCZK;
    private double exportRevenueEUR;
    private double exportRevenueCZK;

    /**
     * Flexible aggregator where you provide a classifier that maps each row's date
     * to a normalized bucket key (e.g., truncate to hour/day/month).
     * The resulting aggregated row's 'date' will be the classifier output.
     */
    public static List<SummaryRow> aggregate(List<SummaryRow> rows, Function<LocalDateTime, LocalDateTime> dateClassifier) {
        Map<LocalDateTime, List<SummaryRow>> grouped = rows.stream()
                .collect(Collectors.groupingBy(r -> dateClassifier.apply(r.getDate())));

        return grouped.entrySet().stream()
                .map(entry -> {
                    List<SummaryRow> group = entry.getValue();

                    double minPriceEUR = group.stream().mapToDouble(SummaryRow::getPriceEUR).min().orElse(0.0);
                    double maxPriceEUR = group.stream().mapToDouble(SummaryRow::getPriceEUR).max().orElse(0.0);

                    double minPriceCZK = group.stream().mapToDouble(SummaryRow::getPriceCZK).min().orElse(0.0);
                    double maxPriceCZK = group.stream().mapToDouble(SummaryRow::getPriceCZK).max().orElse(0.0);

                    return new SummaryRow(
                            entry.getKey(),
                            group.stream().mapToDouble(SummaryRow::getImportCEZ).sum(),
                            group.stream().mapToDouble(SummaryRow::getImportRest).sum(),
                            group.stream().mapToDouble(SummaryRow::getExportCEZ).sum(),
                            group.stream().mapToDouble(SummaryRow::getExportRest).sum(),
                            group.stream().mapToDouble(SummaryRow::getConsumption).sum(),
                            group.stream().mapToDouble(SummaryRow::getYield).sum(),
                            maxPriceEUR - minPriceEUR,
                            maxPriceCZK - minPriceCZK,
                            group.stream().mapToDouble(SummaryRow::getImportCostEUR).sum(),
                            group.stream().mapToDouble(SummaryRow::getImportCostCZK).sum(),
                            group.stream().mapToDouble(SummaryRow::getExportRevenueEUR).sum(),
                            group.stream().mapToDouble(SummaryRow::getExportRevenueCZK).sum()
                    );
                })
                .sorted(Comparator.comparing(SummaryRow::getDate))
                .collect(Collectors.toList());
    }

    public static List<SummaryRow> aggregate(List<SummaryRow> rows, Granularity granularity) {
        return aggregate(rows, granularity.classifier());
    }

    @Getter
    @AllArgsConstructor
    public enum Granularity {
        HOUR(dt -> dt.truncatedTo(ChronoUnit.HOURS)),
        DAY(dt -> dt.toLocalDate().atStartOfDay()),
        MONTH(dt -> YearMonth.from(dt).atDay(1).atStartOfDay());

        @Accessors(fluent = true)
        private final Function<LocalDateTime, LocalDateTime> classifier;
    }
}

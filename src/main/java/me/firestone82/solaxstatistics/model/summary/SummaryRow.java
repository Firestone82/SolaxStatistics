package me.firestone82.solaxstatistics.model.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
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

@Data
@Builder
@AllArgsConstructor
public class SummaryRow {
    private LocalDateTime date;

    // Solax
    private double yield;
    private double consumption;
    private double exportPriceGrid;

    // Import
    private double importGrid;
    private double importSelf;
    private double importCostGrid;
    private double importCostSelf;

    // Export
    private double exportGrid;
    private double exportSelf;
    private double exportRevenueGrid;
    private double exportRevenueSelf;

    // Self consumption
    private double selfConsummated;
    private double savings;
    private double selfUsePercentage;

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

                    return SummaryRow.builder()
                            .date(entry.getKey())
                            .yield(group.stream().mapToDouble(SummaryRow::getYield).sum())
                            .selfUsePercentage(group.stream().mapToDouble(SummaryRow::getSelfUsePercentage).average().orElse(0.0))
                            .exportPriceGrid(0) // group.stream().mapToDouble(SummaryRow::getExportPriceGrid).average().orElse(0.0))
                            .importGrid(group.stream().mapToDouble(SummaryRow::getImportGrid).sum())
                            .importSelf(group.stream().mapToDouble(SummaryRow::getImportSelf).sum())
                            .importCostGrid(group.stream().mapToDouble(SummaryRow::getImportCostGrid).sum())
                            .importCostSelf(group.stream().mapToDouble(SummaryRow::getImportCostSelf).sum())
                            .exportGrid(group.stream().mapToDouble(SummaryRow::getExportGrid).sum())
                            .exportSelf(group.stream().mapToDouble(SummaryRow::getExportSelf).sum())
                            .exportRevenueGrid(group.stream().mapToDouble(SummaryRow::getExportRevenueGrid).sum())
                            .exportRevenueSelf(group.stream().mapToDouble(SummaryRow::getExportRevenueSelf).sum())
                            .consumption(group.stream().mapToDouble(SummaryRow::getConsumption).sum())
                            .selfConsummated(group.stream().mapToDouble(SummaryRow::getSelfConsummated).sum())
                            .savings(group.stream().mapToDouble(SummaryRow::getSavings).sum())
                            .build();
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
        MONTH(dt -> YearMonth.from(dt).atDay(1).atStartOfDay()),
        YEAR(dt -> dt.toLocalDate().withDayOfYear(1).atStartOfDay());

        @Accessors(fluent = true)
        private final Function<LocalDateTime, LocalDateTime> classifier;
    }
}

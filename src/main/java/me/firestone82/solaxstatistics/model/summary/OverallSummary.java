package me.firestone82.solaxstatistics.model.summary;

import lombok.Getter;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Getter
public class OverallSummary {
    private final YearMonth date;
    private final List<SummaryRow> hourly;
    private final List<SummaryRow> daily;
    private final SummaryRow total;

    public OverallSummary(YearMonth date, List<SummaryRow> hourly) {
        this.date = date;
        this.hourly = hourly;

        List<SummaryRow> daily = SummaryRow.aggregate(this.hourly, SummaryRow.Granularity.DAY);
        this.daily = preprocessExportSelf(daily);

        // Calculate self export revenue
        SummaryRow total = SummaryRow.aggregate(daily, SummaryRow.Granularity.MONTH).getFirst();
        this.total = preprocessExportSelf(List.of(total)).getFirst();
    }

    public static List<SummaryRow> preprocessExportSelf(List<SummaryRow> rows) {
        List<SummaryRow> closedRows = new ArrayList<>(rows); // Make mutable copy

        closedRows.forEach(row -> {
            if ((row.getExportSelf() - row.getImportSelf()) > 0) {
                row.setExportRevenueSelf((row.getExportSelf() - row.getImportSelf()) * 3.0);
            }

            int hour = row.getDate().getHour();
            if (hour < 6 || hour >= 19 && hour <= 21) {
                row.setImportCostSelf(row.getImportSelf() * 1.1);
            } else {
                row.setImportCostSelf(row.getImportSelf() * 2.1);
            }

            if (row.getExportSelf() < row.getImportSelf()) {
                double overflow = row.getImportSelf() - row.getExportSelf();
                row.setImportCostSelf(row.getImportCostSelf() + (overflow * 4.5));
            }
        });

        return closedRows;
    }
}

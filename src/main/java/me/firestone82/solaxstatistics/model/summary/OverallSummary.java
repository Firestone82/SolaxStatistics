package me.firestone82.solaxstatistics.model.summary;

import lombok.Getter;

import java.time.YearMonth;
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

        this.daily = SummaryRow.aggregate(this.hourly, SummaryRow.Granularity.DAY);
        this.daily.forEach(day -> {
            // Calculate self export revenue
            if ((day.getExportSelf() - day.getImportSelf()) > 0) {
                day.setExportRevenueSelf((day.getExportSelf() - day.getImportSelf()) * 3.0);
            }

            if (day.getExportSelf() < day.getImportSelf()) {
                double overflow = day.getImportSelf() - day.getExportSelf();
                day.setImportCostSelf(day.getImportCostSelf() + (overflow * 4.5));
            }
        });

        // Calculate self export revenue
        this.total = SummaryRow.aggregate(this.daily, SummaryRow.Granularity.MONTH).getFirst();
    }
}

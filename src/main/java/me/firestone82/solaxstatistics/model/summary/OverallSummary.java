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
        this.total = SummaryRow.aggregate(this.daily, SummaryRow.Granularity.MONTH).getFirst();
    }
}

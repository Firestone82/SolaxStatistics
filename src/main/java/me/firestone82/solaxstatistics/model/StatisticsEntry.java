package me.firestone82.solaxstatistics.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import me.firestone82.solaxstatistics.utils.TimeUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class StatisticsEntry implements Cloneable {
    private LocalDateTime dateTime;
    private double yieldMWh;
    private double exportMWh;
    private double consumptionMWh;
    private double importMWh;

    public void subtract(StatisticsEntry other) {
        this.yieldMWh -= other.yieldMWh;
        this.exportMWh -= other.exportMWh;
        this.consumptionMWh -= other.consumptionMWh;
        this.importMWh -= other.importMWh;
    }

    /**
     * Groups Solax 5-minute deltas into quarters keyed by the quarter START (Solax stamps the END of each interval).
     */
    public static Map<LocalDateTime, StatisticsEntry> aggregateQuarterHourly(List<StatisticsEntry> data) {
        Map<LocalDateTime, List<StatisticsEntry>> byQuarter = data.stream()
                .collect(Collectors.groupingBy(e -> TimeUtils.toQuarterStart(e.getDateTime().minusMinutes(5))));

        return byQuarter.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> new StatisticsEntry(
                        entry.getKey(),
                        entry.getValue().stream().mapToDouble(StatisticsEntry::getYieldMWh).sum(),
                        entry.getValue().stream().mapToDouble(StatisticsEntry::getExportMWh).sum(),
                        entry.getValue().stream().mapToDouble(StatisticsEntry::getConsumptionMWh).sum(),
                        entry.getValue().stream().mapToDouble(StatisticsEntry::getImportMWh).sum()
                )
        ));
    }

    public static Map<LocalDateTime, StatisticsEntry> aggregateDaily(List<StatisticsEntry> data) {
        return data.stream().collect(Collectors.groupingBy(
                e -> e.getDateTime().toLocalDate().atStartOfDay(),
                Collectors.collectingAndThen(Collectors.toList(), list -> new StatisticsEntry(
                        list.getFirst().getDateTime().toLocalDate().atStartOfDay(),
                        list.stream().mapToDouble(StatisticsEntry::getYieldMWh).sum(),
                        list.stream().mapToDouble(StatisticsEntry::getExportMWh).sum(),
                        list.stream().mapToDouble(StatisticsEntry::getConsumptionMWh).sum(),
                        list.stream().mapToDouble(StatisticsEntry::getImportMWh).sum()
                ))
        ));
    }

    @Override
    public StatisticsEntry clone() {
        try {
            return (StatisticsEntry) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}

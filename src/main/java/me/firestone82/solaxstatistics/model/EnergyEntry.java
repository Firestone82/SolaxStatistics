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
@NoArgsConstructor
@AllArgsConstructor
public class EnergyEntry {
    private LocalDateTime dateTime;
    private double importMWh;
    private double exportMWh;

    /**
     * Groups CEZ entries into quarters keyed by the quarter START.
     * CEZ stamps the END of each quarter and reports the average power (kW) over it, hence the 15-minute shift and the
     * division by 4 to get kWh. Values are summed so both copies of the repeated quarters on the DST fall-back day count.
     */
    public static Map<LocalDateTime, EnergyEntry> aggregateQuarterHourly(List<EnergyEntry> data) {
        Map<LocalDateTime, List<EnergyEntry>> byQuarter = data.stream()
                .collect(Collectors.groupingBy(e -> TimeUtils.toQuarterStart(e.getDateTime().minusMinutes(15))));

        return byQuarter.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> new EnergyEntry(
                        entry.getKey(),
                        entry.getValue().stream().mapToDouble(EnergyEntry::getImportMWh).sum() / 4.0,
                        entry.getValue().stream().mapToDouble(EnergyEntry::getExportMWh).sum() / 4.0
                )
        ));
    }
}

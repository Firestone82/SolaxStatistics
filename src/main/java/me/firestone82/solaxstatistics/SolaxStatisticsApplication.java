package me.firestone82.solaxstatistics;

import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxstatistics.service.summary.SummaryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.YearMonth;

@Slf4j
@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class SolaxStatisticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SolaxStatisticsApplication.class, args);
    }

    /**
     * Processes the summary of the months from summary.from to summary.to on startup.
     * Without summary.from the previous (last complete) month is processed.
     */
    @Bean
    public ApplicationRunner processSummaries(
            SummaryService summaryService,
            @Value("${summary.from:}") String from,
            @Value("${summary.to:}") String to
    ) {
        return args -> {
            YearMonth start = from.isBlank() ? YearMonth.now().minusMonths(1) : YearMonth.parse(from.trim());
            YearMonth end = to.isBlank() ? start : YearMonth.parse(to.trim());

            if (end.isBefore(start)) {
                log.warn("Nothing to process, summary.to ({}) is before summary.from ({})", end, start);
                return;
            }

            log.info("Processing summaries from {} to {}", start, end);
            for (YearMonth month = start; !month.isAfter(end); month = month.plusMonths(1)) {
                summaryService.processSummary(month);
            }
        };
    }
}

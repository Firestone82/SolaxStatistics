package me.firestone82.solaxstatistics.configuration.email;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "email")
public class EmailProperties {
    public static final String DEFAULT_TEMPLATE = "energy-report.html";

    /**
     * Send the summary email after processing a month.
     */
    private boolean enabled = false;
    private String sender;

    /**
     * Who receives the summary and in which template (from resources/templates).
     */
    private List<Recipient> recipients = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recipient {
        private String address;
        private String template = DEFAULT_TEMPLATE;
    }
}

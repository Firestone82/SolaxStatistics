package me.firestone82.solaxstatistics.configuration.ote;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ote")
public class OTEProperties {
    /**
     * Base URL of spotovaelektrina.cz, prices are scraped from its day pages {baseUrl}/denni-ceny/{yyyy-MM-dd}.
     */
    private String baseUrl;
}

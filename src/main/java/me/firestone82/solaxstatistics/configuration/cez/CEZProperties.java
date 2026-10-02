package me.firestone82.solaxstatistics.configuration.cez;

import lombok.Data;
import me.firestone82.solaxstatistics.configuration.Credentials;
import me.firestone82.solaxstatistics.configuration.Url;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "cez")
public class CEZProperties {
    /**
     * PND portal (redirects to the ČEZ login) and its data export endpoint.
     */
    private Url url = new Url();
    private Credentials credentials = new Credentials();

    /**
     * Electrometer whose data is exported.
     */
    private long meterId;

    /**
     * Run Chrome without a window.
     */
    private boolean headless = true;
}

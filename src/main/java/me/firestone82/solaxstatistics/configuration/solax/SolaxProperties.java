package me.firestone82.solaxstatistics.configuration.solax;

import lombok.Data;
import me.firestone82.solaxstatistics.configuration.Credentials;
import me.firestone82.solaxstatistics.configuration.Url;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "solax")
public class SolaxProperties {
    /**
     * Only the portal (login page) is used, the application URL is taken from the redirect after login.
     */
    private Url url = new Url();
    private Credentials credentials = new Credentials();

    /**
     * Run Chrome without a window.
     */
    private boolean headless = false;
}

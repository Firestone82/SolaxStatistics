package me.firestone82.solaxstatistics.configuration;

import lombok.Data;

/**
 * Addresses of a scraped portal.
 */
@Data
public class Url {
    /**
     * Page the scraper starts at, it redirects to the login.
     */
    private String portal;

    /**
     * Data export endpoint, for portals which have one.
     */
    private String export;
}

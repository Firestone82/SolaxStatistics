package me.firestone82.solaxstatistics.configuration;

import lombok.Data;
import lombok.ToString;

/**
 * Login of a scraped portal.
 */
@Data
public class Credentials {
    private String username;
    @ToString.Exclude
    private String password;
}

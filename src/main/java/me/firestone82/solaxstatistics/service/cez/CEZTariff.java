package me.firestone82.solaxstatistics.service.cez;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "cez.tariff")
public class CEZTariff {
    private Price importPrice;
    private Price exportFee;

    @Data
    public static class Price {
        private double eur;
        private double czk;
    }
}

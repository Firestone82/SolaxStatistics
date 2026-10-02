package me.firestone82.solaxstatistics.configuration.cez;

import lombok.Data;
import me.firestone82.solaxstatistics.configuration.Price;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "cez.tariff")
public class CEZTariff {
    private Price importPrice;
    private Price exportFee;
}

package me.firestone82.solaxstatistics.serialization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.firestone82.solaxstatistics.serialization.adapter.EnumJsonAdapter;
import me.firestone82.solaxstatistics.serialization.adapter.LocalDateTimeJsonAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
@ConditionalOnClass(Gson.class)
public class GsonService {

    public static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeJsonAdapter())
            .registerTypeHierarchyAdapter(Enum.class, new EnumJsonAdapter())
            .serializeNulls()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    @Bean
    public Gson gson() {
        return gson;
    }
}
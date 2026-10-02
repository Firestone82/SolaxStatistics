package me.firestone82.solaxstatistics.configuration.email;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Keeps the older "email.recipients: a@x.com,b@y.com" form working, each address gets the default template.
 */
@Component
@ConfigurationPropertiesBinding
public class RecipientConverter implements Converter<String, EmailProperties.Recipient> {

    @Override
    public EmailProperties.Recipient convert(String address) {
        return new EmailProperties.Recipient(address.trim(), EmailProperties.DEFAULT_TEMPLATE);
    }
}

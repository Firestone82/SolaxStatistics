package me.firestone82.solaxstatistics.service.smtp;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxstatistics.configuration.email.EmailProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EmailService {
    private static final String TEMPLATE_DIRECTORY = "templates/";

    // <!--{{#rows}}-->...<!--{{/rows}}--> repeats its content for each row of a list,
    // <!--{{^rows}}-->...<!--{{/rows}}--> is shown only when the list is empty
    private static final Pattern SECTION = Pattern.compile("<!--\\{\\{([#^])(\\w+)}}-->(.*?)<!--\\{\\{/\\2}}-->", Pattern.DOTALL);

    private final JavaMailSender mailSender;
    private final EmailProperties properties;

    public EmailService(
            @Autowired JavaMailSender mailSender,
            @Autowired EmailProperties properties
    ) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * Sends the email to every configured recipient, rendered with the template configured for them.
     * Each recipient gets an email of their own, so they do not see each other's address.
     *
     * @param defaultSubject used when the template has no {@code <title>}
     * @return number of emails sent
     */
    public int sendToRecipients(String defaultSubject, Map<String, Object> variables, List<File> attachments) {
        Map<String, List<String>> addressesByTemplate = properties.getRecipients().stream()
                .filter(recipient -> recipient.getAddress() != null && !recipient.getAddress().isBlank())
                .collect(Collectors.groupingBy(
                        recipient -> Objects.requireNonNullElse(recipient.getTemplate(), EmailProperties.DEFAULT_TEMPLATE),
                        LinkedHashMap::new,
                        Collectors.mapping(recipient -> recipient.getAddress().trim(), Collectors.toList())
                ));

        if (addressesByTemplate.isEmpty()) {
            log.warn("No email recipients configured (email.recipients)");
            return 0;
        }

        int sent = 0;
        for (Map.Entry<String, List<String>> entry : addressesByTemplate.entrySet()) {
            String template = entry.getKey();

            RenderedEmail email;
            try {
                email = render(template, variables, defaultSubject);
            } catch (IOException e) {
                log.error("Failed to load email template '{}', not sending it to {}: {}", template, entry.getValue(), e.getMessage());
                continue;
            }

            for (String address : entry.getValue()) {
                log.debug("Sending '{}' email to {} with subject '{}' and attachments {}", template, address, email.subject(), attachments.stream().map(File::getName).toList());

                try {
                    send(address, email, attachments);
                    sent++;
                } catch (MessagingException | UnsupportedEncodingException | MailException e) {
                    log.error("Failed to send '{}' email to {}: {}", template, address, e.getMessage(), e);
                }
            }
        }

        return sent;
    }

    /**
     * Fills the template with the variables. Numbers and dates are formatted for the language of the template
     * ({@code <html lang="...">}), and its {@code <title>} becomes the subject.
     */
    RenderedEmail render(String template, Map<String, Object> variables, String defaultSubject) throws IOException {
        ClassPathResource resource = new ClassPathResource(TEMPLATE_DIRECTORY + template);
        String html = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);

        Element root = Jsoup.parse(html).selectFirst("html");
        String language = root != null ? root.attr("lang") : "";
        Locale locale = Locale.forLanguageTag(language.isBlank() ? "en" : language);

        html = renderSections(html, variables, locale);
        html = renderValues(html, variables, locale);

        String title = Jsoup.parse(html).title();
        return new RenderedEmail(title.isBlank() ? defaultSubject : title, html);
    }

    private static String renderSections(String html, Map<String, Object> variables, Locale locale) {
        Matcher matcher = SECTION.matcher(html);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            boolean inverted = matcher.group(1).equals("^");
            List<?> rows = variables.get(matcher.group(2)) instanceof List<?> list ? list : List.of();
            String block = matcher.group(3);

            StringBuilder rendered = new StringBuilder();
            if (inverted && rows.isEmpty()) {
                rendered.append(block);
            } else if (!inverted) {
                for (Object row : rows) {
                    Map<?, ?> rowValues = row instanceof Map<?, ?> map ? map : Map.of();
                    rendered.append(renderValues(block, rowValues, locale));
                }
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(rendered.toString()));
        }

        matcher.appendTail(result);
        return result.toString();
    }

    private static String renderValues(String html, Map<?, ?> values, Locale locale) {
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getValue() instanceof List<?>)) {
                html = html.replace("{{" + entry.getKey() + "}}", format(entry.getValue(), locale));
            }
        }

        return html;
    }

    private static String format(Object value, Locale locale) {
        return switch (value) {
            case Double number -> {
                NumberFormat numberFormat = NumberFormat.getNumberInstance(locale);
                numberFormat.setGroupingUsed(false);
                numberFormat.setMaximumFractionDigits(3);
                yield numberFormat.format(number);
            }
            case LocalDate date -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale));
            case null -> "";
            default -> value.toString();
        };
    }

    private void send(String address, RenderedEmail email, List<File> attachments) throws MessagingException, UnsupportedEncodingException {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
        helper.setTo(address);
        helper.setFrom(properties.getSender(), "SolaxStatistics");
        helper.setSubject(email.subject());
        helper.setText(email.html(), true);

        for (File attachment : attachments) {
            helper.addAttachment(attachment.getName(), attachment);
        }

        mailSender.send(mimeMessage);
    }

    record RenderedEmail(String subject, String html) {
    }
}

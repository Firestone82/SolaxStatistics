package me.firestone82.solaxstatistics.service.smtp;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class EmailService {
    private final JavaMailSender mailSender;
    private final String sender;
    private final List<String> recipients;

    public EmailService(
            @Autowired JavaMailSender mailSender,
            @Value("${email.sender}") String sender,
            @Value("#{'${email.recipients}'.split(',')}") ArrayList<String> recipients
    ) {
        this.mailSender = mailSender;
        this.sender = sender;
        this.recipients = recipients;
    }

    public void sendEmail(String template, String subject, Map<String, Object> variables, List<File> attachments) {
        log.debug(
                "Sending '{}' email to {} with subject: '{}' and {} attachments",
                template, recipients, subject, attachments.stream().map(File::getName).toList()
        );

        ClassPathResource htmlResource = new ClassPathResource("templates/" + template);
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        String html;

        try {
            html = StreamUtils.copyToString(htmlResource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load email template '{}': {}", template, e.getMessage());
            return;
        }

        // Replace placeholders in the HTML template with actual user data
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            html = html.replace("{{" + entry.getKey() + "}}", entry.getValue().toString());
        }

        try {
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setTo(recipients.toArray(new String[0]));
            helper.setFrom(sender, "SolaxStatistics");
            helper.setSubject(subject);
            helper.setText(html, true);

            // Attach files to the email
            for (File attachment : attachments) {
                helper.addAttachment(attachment.getName(), attachment);
            }
        } catch (UnsupportedEncodingException | MessagingException e) {
            log.error("Failed to create email message: {}", e.getMessage());
            return;
        }

        mailSender.send(mimeMessage);
    }
}


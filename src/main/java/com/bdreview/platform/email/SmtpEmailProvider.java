package com.bdreview.platform.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** EMAIL_PROVIDER=smtp: any SMTP relay (SMTP_HOST/PORT/USER/PASS), STARTTLS on 587 or TLS on 465. */
@Component
@ConditionalOnProperty(name = "app.email.provider", havingValue = "smtp")
public class SmtpEmailProvider implements EmailProvider {

    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
    private final String from;
    private final String replyTo;

    public SmtpEmailProvider(@Value("${app.email.smtp.host}") String host,
                             @Value("${app.email.smtp.port}") int port,
                             @Value("${app.email.smtp.username}") String username,
                             @Value("${app.email.smtp.password}") String password,
                             @Value("${app.email.from}") String from,
                             @Value("${app.email.reply-to:}") String replyTo) {
        if (host.isBlank()) {
            throw new IllegalStateException("EMAIL_PROVIDER=smtp needs SMTP_HOST");
        }
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username.isBlank() ? null : username);
        sender.setPassword(password.isBlank() ? null : password);
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(!username.isBlank()));
        if (port == 465) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        this.from = from;
        this.replyTo = replyTo;
    }

    @Override
    public void send(OutgoingEmail email) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            if (!replyTo.isBlank()) {
                helper.setReplyTo(replyTo);
            }
            helper.setTo(email.to());
            helper.setSubject(email.subject());
            helper.setText(email.text(), email.html());
            sender.send(message);
        } catch (MessagingException e) {
            throw new IllegalStateException("SMTP send failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return "smtp";
    }
}

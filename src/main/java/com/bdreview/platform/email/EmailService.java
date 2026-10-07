package com.bdreview.platform.email;

import com.bdreview.platform.notification.NotificationTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Sends transactional e-mail through the configured {@link EmailProvider}. Messages are rendered from
 * the admin-editable notification templates (title = subject, body = text) in the recipient's
 * language, and handed to the provider on a background thread after the surrounding transaction
 * commits. So a slow or failing provider never holds up or rolls back the request, and an auth
 * response takes the same time whether or not an e-mail goes out (no account enumeration by timing).
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final EmailProvider provider;
    private final NotificationTemplateService templates;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(500), r -> {
                Thread t = new Thread(r, "email-send");
                t.setDaemon(true);
                return t;
            }, (r, e) -> log.error("E-mail queue full, dropping a message"));

    public EmailService(EmailProvider provider, NotificationTemplateService templates) {
        this.provider = provider;
        this.templates = templates;
    }

    public String providerName() {
        return provider.name();
    }

    /** Renders {@code key} in {@code locale} ("en" / "bn") and queues it for {@code to}. */
    public void sendTemplate(NotificationTemplateService.Key key, String locale, String to, Map<String, ?> vars) {
        NotificationTemplateService.Text t = templates.render(key, locale, vars);
        send(to, t.title(), t.body());
    }

    public void send(String to, String subject, String text) {
        OutgoingEmail email = new OutgoingEmail(to, subject, text, toHtml(text));
        Runnable task = () -> {
            try {
                provider.send(email);
            } catch (RuntimeException e) {
                log.error("E-mail to {} (\"{}\") failed via {}: {}", mask(to), subject, provider.name(), e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(task);
                }
            });
        } else {
            executor.execute(task);
        }
    }

    /** Plain text to a minimal, client-safe HTML version (escaped, line breaks kept). */
    static String toHtml(String text) {
        String body = HtmlUtils.htmlEscape(text).replace("\n", "<br>");
        return "<!doctype html><html><body style=\"margin:0;padding:24px;background:#f6f7f9;"
                + "font-family:-apple-system,Segoe UI,Roboto,Noto Sans Bengali,Arial,sans-serif;color:#1f2937\">"
                + "<div style=\"max-width:520px;margin:0 auto;background:#ffffff;border-radius:12px;padding:28px;"
                + "font-size:15px;line-height:1.6\"><div style=\"font-weight:700;font-size:18px;margin-bottom:16px\">Jachai</div>"
                + body + "</div></body></html>";
    }

    /** n***@example.com: addresses never go to the log in full. */
    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" + email.substring(Math.max(at, 0)) : email.charAt(0) + "***" + email.substring(at);
    }
}

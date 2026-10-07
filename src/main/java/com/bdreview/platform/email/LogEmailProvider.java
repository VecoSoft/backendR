package com.bdreview.platform.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Development: logs each e-mail (codes included) instead of sending it. Rejected in production. */
@Component
@ConditionalOnProperty(name = "app.email.provider", havingValue = "log", matchIfMissing = true)
public class LogEmailProvider implements EmailProvider {

    private static final Logger log = LoggerFactory.getLogger(LogEmailProvider.class);

    @Override
    public void send(OutgoingEmail email) {
        log.info("[DEV EMAIL] to={} subject=\"{}\"\n{}", email.to(), email.subject(), email.text());
    }

    @Override
    public String name() {
        return "log";
    }
}

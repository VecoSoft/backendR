package com.bdreview.platform.email;

/**
 * Where e-mail actually goes, picked by EMAIL_PROVIDER: {@code log} (development only: writes the
 * message to the server log; ProductionEnvironmentCheck refuses it in production), {@code smtp}
 * ({@link SmtpEmailProvider}) or {@code resend} (HTTP API, {@link ResendEmailProvider}).
 */
public interface EmailProvider {

    /** Sends one message; throws on failure (the caller logs it, e-mail never fails a request). */
    void send(OutgoingEmail email);

    String name();
}

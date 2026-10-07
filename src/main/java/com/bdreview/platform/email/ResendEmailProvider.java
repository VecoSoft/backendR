package com.bdreview.platform.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EMAIL_PROVIDER=resend: the Resend HTTP API (https://resend.com/docs/api-reference/emails/send-email)
 * with EMAIL_API_KEY. The sending domain in EMAIL_FROM (jachai.com) must be verified in Resend.
 */
@Component
@ConditionalOnProperty(name = "app.email.provider", havingValue = "resend")
public class ResendEmailProvider implements EmailProvider {

    private final RestClient client;
    private final String from;
    private final String replyTo;

    public ResendEmailProvider(@Value("${app.email.api-key}") String apiKey,
                               @Value("${app.email.api-url}") String apiUrl,
                               @Value("${app.email.from}") String from,
                               @Value("${app.email.reply-to:}") String replyTo) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("EMAIL_PROVIDER=resend needs EMAIL_API_KEY");
        }
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(10_000);
        http.setReadTimeout(15_000);
        this.client = RestClient.builder()
                .baseUrl(apiUrl)
                .requestFactory(http)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.from = from;
        this.replyTo = replyTo;
    }

    @Override
    public void send(OutgoingEmail email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", List.of(email.to()));
        body.put("subject", email.subject());
        body.put("text", email.text());
        body.put("html", email.html());
        if (!replyTo.isBlank()) {
            body.put("reply_to", replyTo);
        }
        client.post().contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
    }

    @Override
    public String name() {
        return "resend";
    }
}

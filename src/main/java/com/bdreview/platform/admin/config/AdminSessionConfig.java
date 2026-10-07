package com.bdreview.platform.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Admin panel sessions in Redis ({@code SESSION_STORE=redis}, production), so any API instance can
 * serve any admin request and a deploy doesn't sign admins out. Without it Tomcat keeps sessions in
 * memory, which is fine for local development. The app API itself is stateless (bearer tokens).
 *
 * <p>The cookie follows the same {@code server.servlet.session.cookie.*} settings (COOKIE_DOMAIN,
 * COOKIE_SAME_SITE, COOKIE_SECURE) in both modes.
 */
@Configuration
@ConditionalOnProperty(name = "app.session.store", havingValue = "redis")
@EnableRedisHttpSession(redisNamespace = "jachai:session", maxInactiveIntervalInSeconds = 1800)
public class AdminSessionConfig {

    @Bean
    public CookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.domain:}") String domain,
                                             @Value("${server.servlet.session.cookie.same-site:lax}") String sameSite,
                                             @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("JSESSIONID");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite(capitalize(sameSite));
        if (!domain.isBlank()) {
            serializer.setDomainName(domain);
        }
        // Tomcat's session ids are plain; Spring Session's default Base64 encoding isn't needed.
        serializer.setUseBase64Encoding(false);
        return serializer;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}

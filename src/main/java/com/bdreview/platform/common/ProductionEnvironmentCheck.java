package com.bdreview.platform.common;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail fast: with {@code APP_ENV=production} the API refuses to start unless every setting a real
 * deployment needs is present and not a development default. Runs before any bean is created
 * (registered in META-INF/spring.factories), so a bad .env never half-starts the app or touches the
 * database. Every problem is listed at once.
 */
public class ProductionEnvironmentCheck implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment env = event.getEnvironment();
        if (!"production".equalsIgnoreCase(env.getProperty("app.env", ""))) {
            return;
        }
        List<String> problems = check(env);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("APP_ENV=production but the configuration is not production-ready:\n  - "
                    + String.join("\n  - ", problems));
        }
    }

    static List<String> check(Environment env) {
        List<String> p = new ArrayList<>();
        require(env, p, "DB_HOST", "spring.datasource.url", v -> !v.contains("//localhost:"));
        require(env, p, "DB_PASSWORD", "spring.datasource.password", v -> !v.equals("1234"));
        if (!"require".equals(env.getProperty("DB_SSLMODE")) && !"verify-full".equals(env.getProperty("DB_SSLMODE"))) {
            p.add("DB_SSLMODE must be require (or verify-full) for managed Postgres");
        }
        require(env, p, "JWT_SECRET", "app.jwt.secret", v -> v.length() >= 32 && !v.equals("change-me-in-prod"));
        require(env, p, "CORS_ALLOWED_ORIGINS", "app.cors.allowed-origins", v -> !v.contains("localhost") && v.contains("https://"));
        require(env, p, "STORAGE_BASE_URL", "app.storage.base-url", v -> v.startsWith("https://"));
        if (!"s3".equals(env.getProperty("app.storage.mode"))) {
            p.add("STORAGE_MODE must be s3 (local disk is not allowed in production)");
        } else {
            require(env, p, "SPACES_BUCKET", "app.storage.s3.bucket", v -> true);
            require(env, p, "SPACES_KEY", "app.storage.s3.access-key", v -> true);
            require(env, p, "SPACES_SECRET", "app.storage.s3.secret-key", v -> true);
        }
        if (!"redis".equals(env.getProperty("app.session.store"))) {
            p.add("SESSION_STORE must be redis (admin sessions shared across instances)");
        }
        require(env, p, "REDIS_HOST", "spring.data.redis.host", v -> !v.equals("localhost"));
        if (!"true".equalsIgnoreCase(env.getProperty("server.servlet.session.cookie.secure"))) {
            p.add("COOKIE_SECURE must be true");
        }
        require(env, p, "ML_SERVICE_BASE_URL", "app.ml-service.base-url", v -> !v.contains("localhost"));
        if ("true".equalsIgnoreCase(env.getProperty("app.admin.bootstrap-enabled"))) {
            require(env, p, "ADMIN_DEFAULT_PASSWORD", "app.admin.default-password", v -> v.length() >= 12 && !v.equals("12345678"));
        }
        return p;
    }

    private static void require(Environment env, List<String> problems, String var, String property,
                                java.util.function.Predicate<String> valid) {
        String v = env.getProperty(property, "");
        if (v.isBlank()) {
            problems.add(var + " is missing");
        } else if (!valid.test(v)) {
            problems.add(var + " has a development/unsafe value");
        }
    }
}

package com.bdreview.platform.features;

import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.FeatureDisabledException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Map;

/**
 * Enforces the {@link PlatformFeature} switches on the public JSON API (V63). Runs after the
 * security chain, so the caller's role is known; exceptions go through the normal
 * {@code @RestControllerAdvice}:
 * <ul>
 *   <li>maintenance mode → 503 with the admin's message, for everyone but ADMIN tokens. Login,
 *       token refresh/logout, the public settings endpoint, stored files and the staff API stay up
 *       so the app can render the maintenance screen and staff can keep working;</li>
 *   <li>a feature that is off → 404 "Feature disabled" on every endpoint that belongs to it.</li>
 * </ul>
 * The admin panel (/admin/**) and the staff API (/api/v1/admin/**) are never gated.
 */
@Configuration
public class FeatureGateInterceptor implements WebMvcConfigurer, HandlerInterceptor {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** Always reachable, even in maintenance mode. */
    private static final List<String> MAINTENANCE_EXEMPT = List.of(
            "/api/v1/admin/**",
            "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
            "/api/v1/community/settings",
            "/api/v1/storage/**");

    /** Endpoints owned by each feature (any HTTP method unless the pattern starts with "METHOD "). */
    private static final Map<PlatformFeature, List<String>> GATED = Map.of(
            PlatformFeature.ORDERING, List.of(
                    "/api/v1/businesses/*/orders", "/api/v1/businesses/*/orders/**", "/api/v1/orders/**",
                    "/api/v1/businesses/*/delivery-zones", "/api/v1/businesses/*/delivery-zones/**",
                    "/api/v1/businesses/*/delivery-quote"),
            PlatformFeature.BOOKINGS, List.of(
                    "/api/v1/businesses/*/bookings", "/api/v1/businesses/*/bookings/**", "/api/v1/bookings/**",
                    "/api/v1/businesses/*/commerce/booking"),
            PlatformFeature.COMMUNITY, List.of("/api/v1/community", "/api/v1/community/**"),
            PlatformFeature.PROMOTIONS, List.of("/api/v1/promo/**"),
            PlatformFeature.OWNER_CHAT, List.of("/api/v1/messages", "/api/v1/messages/**"),
            PlatformFeature.NEW_SIGNUPS, List.of("POST /api/v1/auth/register", "POST /api/v1/auth/register-business"));

    /** Still served while their feature is off — the app needs them to know the feature is off. */
    private static final List<String> GATE_EXEMPT = List.of(
            "/api/v1/community/settings", "/api/v1/community/me/standing");

    private final FeatureFlagService flags;

    public FeatureGateInterceptor(FeatureFlagService flags) {
        this.flags = flags;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**").order(-100);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (MATCHER.match("/api/v1/admin/**", path)) {
            return true;
        }
        if (flags.isEnabled(PlatformFeature.MAINTENANCE_MODE) && !CurrentUser.hasRole("ADMIN")
                && MAINTENANCE_EXEMPT.stream().noneMatch(p -> MATCHER.match(p, path))) {
            throw new MaintenanceModeException(flags.maintenanceMessage());
        }
        if (GATE_EXEMPT.contains(path)) {
            return true;
        }
        for (var entry : GATED.entrySet()) {
            if (!flags.isEnabled(entry.getKey()) && entry.getValue().stream().anyMatch(p -> matches(p, request.getMethod(), path))) {
                throw new FeatureDisabledException(entry.getKey().label() + " is currently disabled");
            }
        }
        return true;
    }

    private static boolean matches(String pattern, String method, String path) {
        int space = pattern.indexOf(' ');
        if (space > 0) {
            return pattern.substring(0, space).equalsIgnoreCase(method) && MATCHER.match(pattern.substring(space + 1), path);
        }
        return MATCHER.match(pattern, path);
    }
}

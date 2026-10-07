package com.bdreview.platform.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
// V56: @PreAuthorize on every admin endpoint (JWT API + Thymeleaf admin controllers) — server-side
// role checks in addition to the URL rules below.
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${app.cors.allowed-origins}")
    private List<String> corsAllowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    @Order(2) // evaluated AFTER admin.config.AdminSecurityConfig's @Order(1) chain,
    // which claims everything under /admin/**
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Error/forward dispatches only render a response for a request that was already
                        // authorized (or failed) — never answer them with "session expired". Otherwise a
                        // 500 on an admin page came back as a misleading JSON 401.
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR, jakarta.servlet.DispatcherType.FORWARD).permitAll()
                        .requestMatchers("/error").permitAll()
                        // §5 auth endpoints must be reachable before a token exists
                        .requestMatchers("/api/v1/auth/**", "/actuator/health").permitAll()
                        // caller-specific reads must NOT fall under the public wildcard below —
                        // evaluated first since Spring Security takes the first matching rule
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/businesses/mine").authenticated()
                        // booking availability (Stage 1 slot engine) must be browsable before login,
                        // same as the rest of a business's public page — evaluated before the
                        // broader "/bookings/**" owner-queue rule below
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/businesses/*/bookings/availability").permitAll()
                        // commerce (Phase A orders, Phase C bookings) — the owner queues and owner
                        // commerce settings are caller-specific; keep them off the public GET wildcard
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/businesses/*/orders", "/api/v1/businesses/*/orders/**",
                                "/api/v1/businesses/*/bookings", "/api/v1/businesses/*/bookings/**",
                                "/api/v1/businesses/*/commerce/manage",
                                "/api/v1/businesses/*/qr").authenticated()
                        // Business QR V1 — resolving a scanned token is public, same as the rest
                        // of a business's public page
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/qr/**").permitAll()
                        // public browse/search/profile-view surface (spec §16 consumer capabilities)
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/businesses/**", "/api/v1/categories/**", "/api/v1/cities/**",
                                "/api/v1/areas/**", "/api/v1/attributes/**", "/api/v1/brands/**").permitAll()
                        // home page "Recent Activity" feed — must render for logged-out visitors too
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/reviews/recent").permitAll()
                        // V65 curated homepage content (hero, featured categories) — public like the rest of the homepage
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/home").permitAll()
                        // V67 content pages (Terms, Privacy, FAQ, Help) are public
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/content/*").permitAll()
                        // business page "Overall rating" bar chart — aggregate counts, no review
                        // content, safe to show even while the review list itself stays auth-gated
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/reviews/business/*/rating-breakdown").permitAll()
                        // V56: identity documents are never public. NID images (NID verification is
                        // feature-flagged off) and business-claim documents are ADMIN-only; before V56
                        // anyone holding the object URL could fetch them through the wildcard below.
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/storage/files/nid/**", "/api/v1/storage/files/claim-document/**").hasRole("ADMIN")
                        // pre-signed upload URLs (§13) are bare fetch() PUTs with no Authorization
                        // header — the URL's HMAC signature + expiry (StorageUrlSigner) is the auth
                        // boundary; file GETs apply their own moderation checks (StorageController)
                        .requestMatchers("/api/v1/storage/**").permitAll()
                        // Liveness/readiness probes (Docker healthcheck, load balancers). Status only, no details.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // V56: every /api/v1/admin/** endpoint needs a staff role at the URL level;
                        // controllers narrow it further with @PreAuthorize (settings/roles/reveal = ADMIN).
                        .requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "MODERATOR")
                        // Phase 3 — fire-and-forget page-interaction tracking (sendBeacon has no auth
                        // header). Payload is a fixed enum + an opaque session id; nothing sensitive.
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/businesses/*/events").permitAll()
                        // "Join Community" feed (spec: Facebook-style posts/comments) is a public
                        // read surface like business browse/search above — posting, reacting,
                        // commenting, and editing still fall through to anyRequest().authenticated().
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/community/**").permitAll()
                        // Offers — public browse/detail only (deliberately NOT a blanket
                        // /api/v1/offers/** — that would also permitAll /admin/queue,
                        // /claims/mine, /saved/mine, which must stay behind anyRequest()
                        // .authenticated() below; those single-segment patterns cover just
                        // GET /offers, /offers/{id}, and /offers/business/{id}).
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/offers", "/api/v1/offers/*", "/api/v1/offers/business/*").permitAll()
                        // V58 promotion — public share pages, creative render data and the
                        // sponsored carousel are readable by anyone; the analytics beacon is a
                        // sendBeacon POST without an auth header (opaque session id, no user data).
                        // Every other /api/v1/promo/** (owner tools) stays behind authenticated().
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/promo/public/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/promo/public/events").permitAll()
                        .anyRequest().authenticated())
                // No (valid) bearer token → 401 with a JSON body. Without this Spring falls back to
                // a bare 403, which the app can't tell apart from "not allowed": its refresh-and-retry
                // only runs on 401, so every write made after the access token expired simply failed.
                // An authenticated caller without permission still gets 403.
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\","
                            + "\"message\":\"Your session has expired — please log in again.\",\"path\":\""
                            + request.getRequestURI().replace("\"", "") + "\"}");
                }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder(@org.springframework.beans.factory.annotation.Value("${app.auth.password.bcrypt-cost:12}") int cost) {
        // V70: cost 12. Older cost-10 hashes still verify and are re-hashed on the next app login.
        return new BCryptPasswordEncoder(cost);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsAllowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
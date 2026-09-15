package com.bdreview.platform.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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
                        // §6 OTP + §5 auth endpoints must be reachable before a token exists
                        .requestMatchers("/api/v1/auth/**", "/api/v1/otp/**", "/actuator/health").permitAll()
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
                                "/api/v1/areas/**", "/api/v1/attributes/**").permitAll()
                        // home page "Recent Activity" feed — must render for logged-out visitors too
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/reviews/recent").permitAll()
                        // business page "Overall rating" bar chart — aggregate counts, no review
                        // content, safe to show even while the review list itself stays auth-gated
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/reviews/business/*/rating-breakdown").permitAll()
                        // pre-signed upload URLs (§13) are bare fetch() PUTs with no Authorization
                        // header — the URL itself (unguessable object key) is the auth boundary
                        .requestMatchers("/api/v1/storage/**").permitAll()
                        // Phase 3 — fire-and-forget page-interaction tracking (sendBeacon has no auth
                        // header). Payload is a fixed enum + an opaque session id; nothing sensitive.
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/businesses/*/events").permitAll()
                        // "Join Community" feed (spec: Facebook-style posts/comments) is a public
                        // read surface like business browse/search above — posting, reacting,
                        // commenting, and editing still fall through to anyRequest().authenticated().
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/community/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
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
package com.bdreview.platform.admin.controller;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.health.RecentErrors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * SQA fixes after Phase 3:
 * <ul>
 *   <li>the pending-changes queue renders PENDING rows (it crashed on every row: a SpEL map
 *       lookup with a bare variable name read the literal key, then indexed with null);</li>
 *   <li>an admin page that fails returns a real 500 admin error page with a request id — not a
 *       JSON 401 "session expired" from the /error dispatch — while unauthenticated API calls keep
 *       their JSON 401.</li>
 * </ul>
 * Runs on a real port: the /error forward only happens inside a servlet container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(AdminErrorHandlingIntegrationTest.BrokenAdminController.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/bd_review_it",
        "features.nid-verification.enabled=false",
        "app.admin.bootstrap-enabled=false",
        "app.storage.local-dir=${java.io.tmpdir}/bd-review-it-uploads"
})
class AdminErrorHandlingIntegrationTest {

    private static final String PASSWORD = "Secret-pass-123";

    /** Test-only admin endpoints: one throws in the handler, one fails while rendering its view. */
    @Controller
    @PreAuthorize("hasRole('ADMIN')")
    static class BrokenAdminController {
        @GetMapping("/admin/__test/throws")
        public String throwsInHandler() {
            throw new IllegalStateException("boom in handler");
        }

        @GetMapping("/admin/__test/broken-view")
        public String failsWhileRendering() {
            return "admin/__test_missing_template";
        }
    }

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired RecentErrors recentErrors;

    User admin;
    UUID businessId;
    RequestPostProcessor asAdmin;

    @BeforeEach
    void setUp() {
        admin = userRepository.save(User.builder().phoneNumber("+8801" + suffix().substring(0, 8) + (int) (Math.random() * 10))
                .role(UserRole.ADMIN).name("Admin SQA").otpVerified(true).passwordHash(passwordEncoder.encode(PASSWORD)).build());
        asAdmin = user(admin.getId().toString()).roles("ADMIN");
        UUID categoryId = UUID.randomUUID();
        jdbc.update("INSERT INTO category (id, name, kind) VALUES (?, ?, 'RESTAURANT')", categoryId, "Engineering SQA " + suffix());
        UUID cityId = UUID.randomUUID();
        jdbc.update("INSERT INTO city (id, name) VALUES (?, ?)", cityId, "City SQA " + suffix());
        UUID areaId = UUID.randomUUID();
        jdbc.update("INSERT INTO area (id, city_id, name) VALUES (?, ?, ?)", areaId, cityId, "Area SQA " + suffix());
        businessId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO business (id, owner_user_id, name, slug, category_id, city_id, area_id, contact_number, location, verified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), true)
                """, businessId, admin.getId(), "Akkas Engineering " + suffix(), "sqa-" + businessId, categoryId, cityId, areaId,
                "+88017" + suffix(), 87.0 + Math.random(), 20.0 + Math.random());
        // Mirrors the live row (name → "… SQA"), plus a sparse one: no requester, no phone, no category, no coordinates.
        jdbc.update("""
                INSERT INTO business_pending_change (id, business_id, requested_by, before_json, after_json, status)
                VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), 'PENDING')
                """, UUID.randomUUID(), businessId, admin.getId(),
                "{\"name\": \"Akkas Engineering\", \"areaId\": \"" + areaId + "\", \"cityId\": \"" + cityId + "\", \"latitude\": 23.780636, "
                        + "\"longitude\": 90.419559, \"categoryId\": \"" + categoryId + "\", \"contactNumber\": \"+8801799434241\"}",
                "{\"name\": \"Akkas Engineering SQA\", \"areaId\": \"" + areaId + "\", \"cityId\": \"" + cityId + "\", \"latitude\": 23.780636, "
                        + "\"longitude\": 90.419559, \"categoryId\": \"" + categoryId + "\", \"contactNumber\": \"+8801799434241\"}");
        jdbc.update("""
                INSERT INTO business_pending_change (id, business_id, requested_by, before_json, after_json, status)
                VALUES (?, ?, NULL, CAST(? AS jsonb), CAST(? AS jsonb), 'PENDING')
                """, UUID.randomUUID(), businessId, "{\"name\": \"Old\", \"contactNumber\": null}", "{\"name\": null}");
    }

    // ---------------------------------------------------------------- bug 1

    @Test
    void pendingChangesListRendersPendingRowsIncludingSparseOnes() throws Exception {
        for (String url : new String[]{"/admin/pending-changes", "/admin/pending-changes?status=PENDING"}) {
            MvcResult r = mvc.perform(get(url).with(asAdmin)).andReturn();
            assertThat(r.getResponse().getStatus()).as(url).isEqualTo(200);
            String html = r.getResponse().getContentAsString();
            // Values are read per field now (they all came out empty before), ids shown as names.
            assertThat(html).contains("Akkas Engineering SQA").contains("+8801799434241").contains("Engineering SQA")
                    .contains("diff-changed").contains("—");
        }
    }

    // ---------------------------------------------------------------- bug 2

    @Test
    void throwingAdminControllerReturns500PageWithRequestId() throws Exception {
        MvcResult r = mvc.perform(get("/admin/__test/throws").with(asAdmin)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        String html = r.getResponse().getContentAsString();
        assertThat(html).contains("Something went wrong").doesNotContain("session has expired");
        String id = requestId(html);
        assertThat(recentErrors.latest()).anyMatch(e -> e.path().contains(id) && e.message().contains("boom in handler"));
        // Known errors keep their status.
        assertThat(mvc.perform(get("/admin/chat-reports/" + UUID.randomUUID()).with(asAdmin)).andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void signedInAdminGetsA500PageNotA401ThroughTheRealErrorDispatch() throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://localhost:" + port;

        String loginPage = client.send(HttpRequest.newBuilder(URI.create(base + "/admin/login")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        Matcher csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(loginPage);
        assertThat(csrf.find()).isTrue();
        String form = "phoneNumber=" + enc(admin.getPhoneNumber()) + "&password=" + enc(PASSWORD) + "&_csrf=" + enc(csrf.group(1));
        HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(base + "/admin/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.headers().firstValue("Location").orElse("")).endsWith("/admin");

        for (String path : new String[]{"/admin/__test/throws", "/admin/__test/broken-view"}) {
            HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Accept", "text/html").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).as(path).isEqualTo(500);
            assertThat(res.body()).as(path).contains("Something went wrong").doesNotContain("session has expired");
            requestId(res.body());
        }
        // The real pending-changes page works end to end too.
        assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/admin/pending-changes")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

        // A genuinely unauthenticated API call still gets the JSON 401.
        HttpResponse<String> api = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/api/v1/notifications")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(api.statusCode()).isEqualTo(401);
        assertThat(api.body()).contains("\"status\":401");
    }

    private static String requestId(String html) {
        Matcher m = Pattern.compile("<code[^>]*>([0-9a-f]{8})</code>").matcher(html);
        assertThat(m.find()).as("request id on the page").isTrue();
        return m.group(1);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String suffix() {
        String n = String.valueOf(System.nanoTime());
        return n.substring(n.length() - 8);
    }
}

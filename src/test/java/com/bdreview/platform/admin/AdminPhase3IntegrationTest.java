package com.bdreview.platform.admin;

import com.bdreview.platform.adminconfig.AdminConfigService;
import com.bdreview.platform.admin.security.AdminAuthorities;
import com.bdreview.platform.admin.security.Totp;
import com.bdreview.platform.auth.JwtService;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.community.settings.CommunitySettingsService;
import com.bdreview.platform.features.PlatformSettingStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Admin panel Phase 3 (V67) end to end: admin permission roles, 2FA login, broadcasts and
 * templates, scheduled-job "Run now", support inbox, chat moderation privacy, content pages,
 * analytics/search log, protected-edit cancel, merged-slug lookup and the community comment limit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/bd_review_it",
        "features.nid-verification.enabled=false",
        "photos.approval-required=true",
        "app.admin.bootstrap-enabled=false",
        "app.storage.local-dir=${java.io.tmpdir}/bd-review-it-uploads"
})
class AdminPhase3IntegrationTest {

    private static final String PASSWORD = "Secret-pass-123";

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PlatformSettingStore settingStore;
    @Autowired AdminConfigService adminConfig;
    @Autowired CommunitySettingsService communitySettings;

    User superAdmin;
    User moderatorAdmin;
    User supportAdmin;
    User financeAdmin;
    User owner;
    User member;
    User otherMember;
    String ownerToken;
    String memberToken;
    String otherToken;
    UUID categoryId;
    UUID cityId;
    UUID areaId;
    UUID businessId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM platform_setting");
        settingStore.evict();
        jdbc.update("DELETE FROM admin_config");
        adminConfig.evict();
        // Fixtures from earlier runs of this suite stay out of other suites' duplicate-finder results.
        jdbc.update("""
                UPDATE business SET location = ST_SetSRID(ST_MakePoint(88 + random() * 4, 21 + random() * 4), 4326)
                WHERE slug LIKE 'p3-%' AND ST_X(location::geometry) BETWEEN 90.3 AND 90.4
                """);

        superAdmin = saveAdmin("SUPER_ADMIN");
        moderatorAdmin = saveAdmin("MODERATOR");
        supportAdmin = saveAdmin("SUPPORT");
        financeAdmin = saveAdmin("FINANCE");
        owner = saveUser(UserRole.BUSINESS_OWNER, null);
        member = saveUser(UserRole.CONSUMER, "p3m" + suffix());
        otherMember = saveUser(UserRole.CONSUMER, "p3o" + suffix());
        ownerToken = jwtService.generateAccessToken(owner.getId(), UserRole.BUSINESS_OWNER);
        memberToken = jwtService.generateAccessToken(member.getId(), UserRole.CONSUMER);
        otherToken = jwtService.generateAccessToken(otherMember.getId(), UserRole.CONSUMER);

        categoryId = UUID.randomUUID();
        jdbc.update("INSERT INTO category (id, name, kind) VALUES (?, ?, 'RESTAURANT')", categoryId, "Food P3 " + suffix());
        cityId = UUID.randomUUID();
        jdbc.update("INSERT INTO city (id, name) VALUES (?, ?)", cityId, "City P3 " + suffix());
        areaId = UUID.randomUUID();
        jdbc.update("INSERT INTO area (id, city_id, name) VALUES (?, ?, ?)", areaId, cityId, "Area P3 " + suffix());
        businessId = insertBusiness(owner.getId(), areaId, "Phase Three Kitchen " + suffix());
    }

    // =================================================================
    // Roles: each admin role sees and calls only its sections
    // =================================================================

    @Test
    void eachAdminRoleReachesOnlyItsSections() throws Exception {
        Map<String, List<String>> allowed = Map.of(
                "SUPER_ADMIN", List.of("/admin/analytics", "/admin/support", "/admin/chat-reports", "/admin/health",
                        "/admin/notifications", "/admin/content", "/admin/security", "/admin/photos", "/admin/users", "/admin/dashboard"),
                "MODERATOR", List.of("/admin/chat-reports", "/admin/photos", "/admin/reports", "/admin/dashboard"),
                "SUPPORT", List.of("/admin/support", "/admin/users", "/admin/commerce", "/admin/dashboard"),
                "FINANCE", List.of("/admin/analytics", "/admin/promotions/revenue", "/admin/dashboard"));
        List<String> all = List.of("/admin/analytics", "/admin/support", "/admin/chat-reports", "/admin/health",
                "/admin/notifications", "/admin/content", "/admin/security", "/admin/photos", "/admin/users",
                "/admin/commerce", "/admin/reports", "/admin/promotions/revenue", "/admin/settings", "/admin/audit-log");
        Map<String, User> who = Map.of("SUPER_ADMIN", superAdmin, "MODERATOR", moderatorAdmin,
                "SUPPORT", supportAdmin, "FINANCE", financeAdmin);
        for (var e : who.entrySet()) {
            for (String url : all) {
                int status = mvc.perform(get(url).with(as(e.getValue()))).andReturn().getResponse().getStatus();
                boolean ok = allowed.get(e.getKey()).contains(url) || "SUPER_ADMIN".equals(e.getKey());
                assertThat(status).as(e.getKey() + " GET " + url).isEqualTo(ok ? 200 : 403);
            }
        }
        // Writes are enforced too, not only the pages.
        int s = mvc.perform(post("/admin/notifications/broadcasts").with(as(supportAdmin)).with(csrf())
                .param("title", "x").param("body", "y").param("audience", "ALL_USERS").param("reason", "r"))
                .andReturn().getResponse().getStatus();
        assertThat(s).isEqualTo(403);
        s = mvc.perform(post("/admin/health/jobs/order-auto-cancel/run").with(as(financeAdmin)).with(csrf()).param("reason", "r"))
                .andReturn().getResponse().getStatus();
        assertThat(s).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM broadcast WHERE created_by = ?", supportAdmin.getId())).isZero();

        // The sidebar only lists allowed sections.
        String supportSidebar = mvc.perform(get("/admin/support").with(as(supportAdmin))).andReturn().getResponse().getContentAsString();
        assertThat(supportSidebar).contains("/admin/support").doesNotContain("href=\"/admin/analytics\"")
                .doesNotContain("href=\"/admin/health\"").doesNotContain("href=\"/admin/security\"");
    }

    // =================================================================
    // 2FA
    // =================================================================

    @Test
    void adminWithTwoFactorCannotSignInWithoutTheCode() throws Exception {
        String secret = Totp.newSecret();
        jdbc.update("UPDATE app_user SET totp_secret = ?, totp_enabled = true, totp_enabled_at = now() WHERE id = ?",
                secret, superAdmin.getId());

        MvcResult noCode = mvc.perform(post("/admin/login").with(csrf())
                .param("phoneNumber", superAdmin.getPhoneNumber()).param("password", PASSWORD)).andReturn();
        assertThat(noCode.getResponse().getRedirectedUrl()).contains("/admin/login?error");

        MvcResult wrongCode = mvc.perform(post("/admin/login").with(csrf())
                .param("phoneNumber", superAdmin.getPhoneNumber()).param("password", PASSWORD).param("otp", "000000")).andReturn();
        assertThat(wrongCode.getResponse().getRedirectedUrl()).contains("/admin/login?error");

        MvcResult ok = mvc.perform(post("/admin/login").with(csrf())
                .param("phoneNumber", superAdmin.getPhoneNumber()).param("password", PASSWORD).param("otp", Totp.codeNow(secret))).andReturn();
        assertThat(ok.getResponse().getRedirectedUrl()).isEqualTo("/admin");

        // Without 2FA the password alone still works.
        MvcResult plain = mvc.perform(post("/admin/login").with(csrf())
                .param("phoneNumber", supportAdmin.getPhoneNumber()).param("password", PASSWORD)).andReturn();
        assertThat(plain.getResponse().getRedirectedUrl()).isEqualTo("/admin");
    }

    // =================================================================
    // Broadcast audience + templates
    // =================================================================

    @Test
    void broadcastReachesOnlyTheSelectedAudience() throws Exception {
        UUID otherArea = UUID.randomUUID();
        jdbc.update("INSERT INTO area (id, city_id, name) VALUES (?, ?, ?)", otherArea, cityId, "Elsewhere P3 " + suffix());
        User otherOwner = saveUser(UserRole.BUSINESS_OWNER, null);
        insertBusiness(otherOwner.getId(), otherArea, "Far Away Shop " + suffix());
        String title = "Owners in the area " + suffix();

        mvc.perform(post("/admin/notifications/broadcasts").with(as(superAdmin)).with(csrf())
                .param("title", title).param("body", "Hello area owners")
                .param("audience", "OWNERS").param("areaId", areaId.toString()).param("reason", "Area announcement"));


        assertThat(count("SELECT count(*) FROM notification WHERE type = 'BROADCAST' AND title = ? AND recipient_user_id = ?",
                title, owner.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification WHERE type = 'BROADCAST' AND title = ? AND recipient_user_id = ?",
                title, otherOwner.getId())).isZero();
        assertThat(count("SELECT count(*) FROM notification WHERE type = 'BROADCAST' AND title = ? AND recipient_user_id = ?",
                title, member.getId())).isZero();
        assertThat(count("SELECT count(*) FROM broadcast WHERE title = ? AND status = 'SENT' AND recipient_count = 1", title)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action LIKE 'BROADCAST%'")).isPositive();

        // Scheduled → cancellable, and nothing is delivered.
        mvc.perform(post("/admin/notifications/broadcasts").with(as(superAdmin)).with(csrf())
                .param("title", "Later").param("body", "Scheduled one").param("audience", "ALL_USERS")
                .param("scheduledAt", java.time.LocalDateTime.now().plusDays(2).withNano(0).toString()).param("reason", "Plan"));
        UUID scheduled = jdbc.queryForObject("SELECT id FROM broadcast WHERE title = 'Later' ORDER BY created_at DESC LIMIT 1", UUID.class);
        mvc.perform(post("/admin/notifications/broadcasts/" + scheduled + "/cancel").with(as(superAdmin)).with(csrf()).param("reason", "Not needed"));
        assertThat(jdbc.queryForObject("SELECT status FROM broadcast WHERE id = ?", String.class, scheduled)).isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM notification WHERE related_entity_id = ?", scheduled)).isZero();
    }

    @Test
    void editedTemplateIsUsedForTheNextNotification() throws Exception {
        mvc.perform(post("/admin/notifications/templates/PHOTO_REJECTED/en").with(as(superAdmin)).with(csrf())
                .param("action", "save").param("title", "Custom: your {photoType} was declined")
                .param("body", "Because {reason}.").param("reason", "Friendlier wording"));
        UUID photoId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO photo_moderation (id, source_type, source_id, url, uploader_user_id, status)
                VALUES (?, 'REVIEW', ?, 'https://example.com/p3.jpg', ?, 'PENDING')
                """, photoId, UUID.randomUUID(), member.getId());
        mvc.perform(post("/admin/photos/" + photoId + "/reject").with(as(superAdmin)).with(csrf()).param("reason", "blurry"));

        Map<String, Object> n = jdbc.queryForMap(
                "SELECT title, body FROM notification WHERE recipient_user_id = ? ORDER BY created_at DESC LIMIT 1", member.getId());
        assertThat(n.get("title")).isEqualTo("Custom: your review was declined");
        assertThat(n.get("body")).isEqualTo("Because blurry.");
    }

    // =================================================================
    // Scheduled jobs
    // =================================================================

    @Test
    void runNowExecutesTheJobAndRecordsTheRun() throws Exception {
        UUID stale = insertOrder(businessId, member.getId(), 3 * 24 * 60);
        long before = count("SELECT count(*) FROM scheduled_job_run WHERE job_name = 'order-auto-cancel' AND manual");
        mvc.perform(post("/admin/health/jobs/order-auto-cancel/run").with(as(superAdmin)).with(csrf()).param("reason", "Test run"));
        assertThat(count("SELECT count(*) FROM scheduled_job_run WHERE job_name = 'order-auto-cancel' AND manual AND result = 'OK'"))
                .isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT status FROM business_order WHERE id = ?", String.class, stale)).isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'JOB_RUN_NOW'")).isPositive();
        for (String url : List.of("/admin/health?job=order-auto-cancel", "/admin/notifications/templates",
                "/admin/notifications/templates?key=ORDER_STATUS", "/admin/account/2fa", "/admin/security")) {
            assertThat(mvc.perform(get(url).with(as(superAdmin))).andReturn().getResponse().getStatus()).as(url).isEqualTo(200);
        }
        String page = mvc.perform(get("/admin/health").with(as(superAdmin))).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("order-auto-cancel").contains("Database");
    }

    // =================================================================
    // Chat moderation
    // =================================================================

    @Test
    void unreportedChatsAreNotReadableByAdmins() throws Exception {
        UUID threadId = insertThread(member.getId(), businessId);
        insertMessage(threadId, member.getId(), "Private hello " + suffix());

        // There is no way in for a conversation nobody reported.
        String queue = mvc.perform(get("/admin/chat-reports?status=ALL").with(as(superAdmin))).andReturn().getResponse().getContentAsString();
        assertThat(queue).doesNotContain("Private hello");
        assertThat(mvc.perform(get("/admin/chat-reports/" + threadId).with(as(superAdmin))).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        // Outsiders can't read it through the app API either, nor report it.
        assertThat(call(HttpMethod.GET, "/api/v1/messages/threads/" + threadId, otherToken, null).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/api/v1/messages/threads/" + threadId + "/report", otherToken,
                Map.of("reason", "SPAM")).getResponse().getStatus()).isEqualTo(403);

        // The owner reports it → now (and only now) moderators can read it.
        insertMessage(threadId, member.getId(), "Pay me outside the app " + suffix());
        MvcResult r = call(HttpMethod.POST, "/api/v1/messages/threads/" + threadId + "/report", ownerToken,
                Map.of("reason", "SCAM", "details", "Asked for bKash"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        UUID reportId = UUID.fromString(json(r).get("id").asText());
        assertThat(jdbc.queryForObject("SELECT reported_user_id FROM chat_report WHERE id = ?", UUID.class, reportId)).isEqualTo(member.getId());
        String view = mvc.perform(get("/admin/chat-reports/" + reportId).with(as(moderatorAdmin))).andReturn().getResponse().getContentAsString();
        assertThat(view).contains("Pay me outside the app");
        // SUPPORT has no content-moderation permission.
        assertThat(mvc.perform(get("/admin/chat-reports/" + reportId).with(as(supportAdmin))).andReturn().getResponse().getStatus()).isEqualTo(403);

        // Block the sender for 7 days → their next message is refused.
        mvc.perform(post("/admin/chat-reports/" + reportId + "/action").with(as(moderatorAdmin)).with(csrf())
                .param("action", "block").param("days", "7").param("reason", "Scam attempt"));
        assertThat(jdbc.queryForObject("SELECT action FROM chat_report WHERE id = ?", String.class, reportId)).isEqualTo("BLOCK");
        assertThat(call(HttpMethod.POST, "/api/v1/messages/threads/" + threadId + "/reply", memberToken,
                Map.of("content", "hello?")).getResponse().getStatus()).isEqualTo(403);
        assertThat(json(call(HttpMethod.GET, "/api/v1/messages/can-send", memberToken, null)).get("canSend").asBoolean()).isFalse();
        assertThat(count("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'CHAT_SENDER_BLOCKED'", reportId)).isEqualTo(1);
        // The owner can still message.
        assertThat(call(HttpMethod.POST, "/api/v1/messages/threads/" + threadId + "/reply", ownerToken,
                Map.of("content", "We don't take payment outside")).getResponse().getStatus()).isEqualTo(200);
    }

    // =================================================================
    // Support inbox
    // =================================================================

    @Test
    void supportTicketReplyNotifiesTheUserAndNotesStayInternal() throws Exception {
        MvcResult opened = call(HttpMethod.POST, "/api/v1/support/tickets", memberToken,
                Map.of("category", "ORDER", "subject", "Order never arrived", "message", "Order T123 shows delivered."));
        assertThat(opened.getResponse().getStatus()).isEqualTo(200);
        UUID ticketId = UUID.fromString(json(opened).get("id").asText());

        assertThat(mvc.perform(get("/admin/support").with(as(supportAdmin))).andReturn().getResponse().getContentAsString())
                .contains("Order never arrived");
        mvc.perform(post("/admin/support/" + ticketId + "/note").with(as(supportAdmin)).with(csrf()).param("message", "Check with the shop"));
        mvc.perform(post("/admin/support/" + ticketId + "/reply").with(as(supportAdmin)).with(csrf())
                .param("message", "We've refunded you.").param("status", "RESOLVED"));

        assertThat(count("SELECT count(*) FROM notification WHERE recipient_user_id = ? AND type = 'SUPPORT_REPLY' AND related_entity_id = ?",
                member.getId(), ticketId)).isEqualTo(1);
        String mine = call(HttpMethod.GET, "/api/v1/support/tickets/" + ticketId, memberToken, null).getResponse().getContentAsString();
        assertThat(mine).contains("We've refunded you.").doesNotContain("Check with the shop");
        assertThat(jdbc.queryForObject("SELECT status FROM support_ticket WHERE id = ?", String.class, ticketId)).isEqualTo("RESOLVED");
        String view = mvc.perform(get("/admin/support/" + ticketId).with(as(supportAdmin))).andReturn().getResponse().getContentAsString();
        assertThat(view).contains("Check with the shop").contains("refunded you.").contains("/admin/users/" + member.getId());
        // Another user can't read it.
        assertThat(call(HttpMethod.GET, "/api/v1/support/tickets/" + ticketId, otherToken, null).getResponse().getStatus()).isEqualTo(404);
        assertThat(count("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action IN ('SUPPORT_REPLIED', 'SUPPORT_NOTE_ADDED')", ticketId))
                .isEqualTo(2);
    }

    // =================================================================
    // Content pages, analytics, leftovers
    // =================================================================

    @Test
    void contentPageSaveIsVersionedAndPublic() throws Exception {
        mvc.perform(post("/admin/content/terms/en").with(as(superAdmin)).with(csrf())
                .param("title", "Terms of use").param("bodyMd", "## Rules\nBe kind. " + suffix()).param("reason", "First version"));
        mvc.perform(post("/admin/content/terms/en").with(as(superAdmin)).with(csrf())
                .param("title", "Terms of use").param("bodyMd", "## Rules\nBe kind and honest.").param("reason", "Clarify"));
        JsonNode page = json(call(HttpMethod.GET, "/api/v1/content/terms?lang=en", null, null));
        assertThat(page.get("bodyMd").asText()).contains("honest");
        assertThat(page.get("version").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(page.get("updatedAt").isNull()).isFalse();
        assertThat(count("SELECT count(*) FROM content_page_version WHERE slug = 'terms' AND locale = 'en'")).isGreaterThanOrEqualTo(2);
        UUID v1 = jdbc.queryForObject("SELECT id FROM content_page_version WHERE slug = 'terms' AND locale = 'en' ORDER BY version DESC LIMIT 1", UUID.class);
        for (String url : List.of("/admin/content", "/admin/content/terms/en", "/admin/content/terms/en?version=" + v1, "/admin/content/faq/bn")) {
            assertThat(mvc.perform(get(url).with(as(superAdmin))).andReturn().getResponse().getStatus()).as(url).isEqualTo(200);
        }
        // Bangla falls back to the written English page.
        assertThat(json(call(HttpMethod.GET, "/api/v1/content/terms?lang=bn", null, null)).get("bodyMd").asText()).contains("honest");
    }

    @Test
    void analyticsRendersAndZeroResultSearchesAreLogged() throws Exception {
        String q = "zzqx" + suffix();
        call(HttpMethod.GET, "/api/v1/businesses/search?q=" + q, null, null);
        for (int i = 0; i < 50 && count("SELECT count(*) FROM search_log WHERE normalized = ?", q) == 0; i++) {
            Thread.sleep(100);
        }
        assertThat(count("SELECT count(*) FROM search_log WHERE normalized = ? AND result_count = 0", q)).isEqualTo(1);

        for (int days : List.of(7, 30, 90)) {
            String html = mvc.perform(get("/admin/analytics?days=" + days).with(as(financeAdmin))).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("Daily activity");
        }
        for (String table : List.of("daily", "top-queries", "zero-results", "reports-by-reason")) {
            MvcResult csv = mvc.perform(get("/admin/analytics/export/" + table + ".csv?days=30").with(as(financeAdmin))).andReturn();
            assertThat(csv.getResponse().getStatus()).as(table).isEqualTo(200);
            assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
            if (table.equals("zero-results")) {
                assertThat(csv.getResponse().getContentAsString()).contains(q);
            }
        }
    }

    @Test
    void ownerCanWithdrawAPendingProtectedEdit() throws Exception {
        jdbc.update("UPDATE business SET verified = true WHERE id = ?", businessId);
        String oldName = jdbc.queryForObject("SELECT name FROM business WHERE id = ?", String.class, businessId);
        assertThat(call(HttpMethod.PUT, "/api/v1/businesses/" + businessId, ownerToken, updateBody("Renamed P3 " + suffix()))
                .getResponse().getStatus()).isEqualTo(200);
        JsonNode pending = json(call(HttpMethod.GET, "/api/v1/businesses/" + businessId + "/pending-changes", ownerToken, null));
        UUID changeId = UUID.fromString(pending.get("id").asText());

        // Someone else can't cancel it.
        assertThat(call(HttpMethod.DELETE, "/api/v1/businesses/" + businessId + "/pending-changes/" + changeId, memberToken, null)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(call(HttpMethod.DELETE, "/api/v1/businesses/" + businessId + "/pending-changes/" + changeId, ownerToken, null)
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT status FROM business_pending_change WHERE id = ?", String.class, changeId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT name FROM business WHERE id = ?", String.class, businessId)).isEqualTo(oldName);
        assertThat(call(HttpMethod.GET, "/api/v1/businesses/" + businessId + "/pending-changes", ownerToken, null)
                .getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void mergedSlugLookupPointsToTheKeptListing() throws Exception {
        String keptSlug = jdbc.queryForObject("SELECT slug FROM business WHERE id = ?", String.class, businessId);
        String old = "gone-" + suffix();
        jdbc.update("INSERT INTO business_slug_redirect (old_slug, business_id) VALUES (?, ?)", old, businessId);
        MvcResult hit = call(HttpMethod.GET, "/api/v1/businesses/slug-redirects/" + old, null, null);
        assertThat(hit.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(hit).get("slug").asText()).isEqualTo(keptSlug);
        assertThat(call(HttpMethod.GET, "/api/v1/businesses/slug-redirects/never-existed-" + suffix(), null, null)
                .getResponse().getStatus()).isEqualTo(204);
        // The API page route itself answers 301 for the old slug.
        assertThat(call(HttpMethod.GET, "/api/v1/businesses/" + old, null, null).getResponse().getStatus()).isEqualTo(301);
    }

    @Test
    void eleventhCommentWithinAnHourFromANewUserIs429WithTenPerHourConfigured() throws Exception {
        String stored = jdbc.queryForObject("SELECT coalesce(settings::text, '{}') FROM community_settings WHERE id = 1", String.class);
        ObjectNode settings = (ObjectNode) objectMapper.readTree(stored == null ? "{}" : stored);
        ObjectNode limits = settings.has("rateLimits") ? (ObjectNode) settings.get("rateLimits") : settings.putObject("rateLimits");
        ObjectNode newUsers = limits.has("newUsers") ? (ObjectNode) limits.get("newUsers") : limits.putObject("newUsers");
        newUsers.put("commentsPerHour", 10);
        newUsers.put("postsPerDay", 5);
        newUsers.put("votesPerMinute", 30);
        newUsers.put("reportsPerDay", 10);
        jdbc.update("UPDATE community_settings SET settings = CAST(? AS jsonb) WHERE id = 1", objectMapper.writeValueAsString(settings));
        communitySettings.evict();
        try {
            assertThat(communitySettings.settings().getRateLimits().getNewUsers().getCommentsPerHour()).isEqualTo(10);
            UUID postId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO community_post (id, author_user_id, body, post_type, topic, status, created_at, updated_at, version)
                    VALUES (?, ?, ?, 'DISCUSSION', 'GENERAL', 'ACTIVE', now(), now(), 0)
                    """, postId, otherMember.getId(), "A post to comment on " + suffix());
            for (int i = 1; i <= 10; i++) {
                assertThat(call(HttpMethod.POST, "/api/v1/community/posts/" + postId + "/comments", memberToken,
                        Map.of("content", "Comment number " + i + " here")).getResponse().getStatus()).as("comment " + i).isEqualTo(200);
            }
            assertThat(call(HttpMethod.POST, "/api/v1/community/posts/" + postId + "/comments", memberToken,
                    Map.of("content", "Comment number 11 here")).getResponse().getStatus()).isEqualTo(429);
        } finally {
            jdbc.update("UPDATE community_settings SET settings = CAST(? AS jsonb) WHERE id = 1", stored == null ? "{}" : stored);
            communitySettings.evict();
        }
    }

    // =================================================================
    // helpers
    // =================================================================

    private RequestPostProcessor as(User admin) {
        User fresh = userRepository.findById(admin.getId()).orElseThrow();
        return user(admin.getId().toString()).authorities(AdminAuthorities.of(fresh));
    }

    private User saveAdmin(String adminRole) {
        User u = saveUser(UserRole.ADMIN, null);
        jdbc.update("UPDATE app_user SET admin_role = ? WHERE id = ?", adminRole, u.getId());
        return userRepository.findById(u.getId()).orElseThrow();
    }

    private User saveUser(UserRole role, String communityUsername) {
        return userRepository.save(User.builder()
                .phoneNumber("+8801" + suffix().substring(0, 8) + (int) (Math.random() * 10))
                .role(role)
                .name(role + " P3 tester")
                .otpVerified(true)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .communityUsername(communityUsername == null ? null : communityUsername.substring(0, Math.min(20, communityUsername.length())))
                .communityGender(communityUsername == null ? null : "M")
                .build());
    }

    private UUID insertBusiness(UUID ownerId, UUID area, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO business (id, owner_user_id, name, slug, category_id, city_id, area_id, contact_number, location, verified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), false)
                """, id, ownerId, name, "p3-" + id, categoryId, cityId, area, "+88017" + suffix(),
                // Spread out, away from other suites' fixtures, so the duplicate finder never pairs them.
                88.0 + Math.random() * 4, 21.0 + Math.random() * 4);
        return id;
    }

    private UUID insertOrder(UUID business, UUID customer, int minutesAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO business_order (id, business_id, customer_user_id, order_number, status, fulfillment_type, subtotal,
                                            total_amount, payment_method, customer_name_snapshot, customer_phone_snapshot, created_at)
                VALUES (?, ?, ?, ?, 'PENDING', 'PICKUP', 500, 500, 'PAY_AT_BUSINESS', 'Test Customer', '+8801700000000',
                        now() - make_interval(mins => ?))
                """, id, business, customer, "P3" + suffix() + (int) (Math.random() * 1000), minutesAgo);
        return id;
    }

    private UUID insertThread(UUID consumer, UUID business) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO message_thread (id, consumer_user_id, business_id) VALUES (?, ?, ?)", id, consumer, business);
        return id;
    }

    private void insertMessage(UUID thread, UUID sender, String content) {
        jdbc.update("INSERT INTO message (id, thread_id, sender_user_id, content) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), thread, sender, content);
    }

    private Map<String, Object> updateBody(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("categoryId", categoryId);
        m.put("cityId", cityId);
        m.put("areaId", areaId);
        m.put("contactNumber", jdbc.queryForObject("SELECT contact_number FROM business WHERE id = ?", String.class, businessId));
        m.put("description", "Still the same place");
        m.put("latitude", 23.8069);
        m.put("longitude", 90.3687);
        m.put("priceTier", "MODERATE");
        m.put("attributeIds", new java.util.ArrayList<>());
        return m;
    }

    private MvcResult call(HttpMethod method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder b = request(method, url).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            b.content(objectMapper.writeValueAsString(body));
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(b).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static String suffix() {
        String n = String.valueOf(System.nanoTime());
        return n.substring(n.length() - 8);
    }
}

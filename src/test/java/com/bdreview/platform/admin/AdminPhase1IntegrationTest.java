package com.bdreview.platform.admin;

import com.bdreview.platform.auth.JwtService;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.community.settings.CommunitySettingsService;
import com.bdreview.platform.features.PlatformSettingStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Admin panel Phase 1 (V63) end to end, through the real HTTP layer: photo moderation, platform
 * user control and DB-backed feature flags. Same throwaway database as the other integration
 * tests ({@code bd_review_it}).
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
class AdminPhase1IntegrationTest {

    private static final String PASSWORD = "Secret-pass-123";
    private static final java.time.Instant RUN_STARTED = java.time.Instant.now();
    private static final String FILES = "http://localhost:8085/api/v1/storage/files/";

    @Autowired MockMvc mvc;
    @Autowired com.bdreview.platform.gallery.StorageUrlSigner storageUrls;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PlatformSettingStore settingStore;
    @Autowired CommunitySettingsService communitySettings;

    User admin;
    User moderator;
    User owner;
    User member;
    String ownerToken;
    String memberToken;
    String adminToken;
    UUID businessId;
    UUID categoryId;
    UUID cityId;
    UUID areaId;
    RequestPostProcessor asAdmin;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM platform_setting");
        settingStore.evict();
        // The IT database persists between runs: retire this suite's listings from earlier runs (same name,
        // phone and spot every time) so they don't flood other suites' duplicate-finder results.
        jdbc.update("UPDATE business SET deleted_at = now() WHERE slug LIKE 'p1-%' AND deleted_at IS NULL AND created_at < ?",
                java.sql.Timestamp.from(RUN_STARTED));
        jdbc.update("UPDATE community_settings SET settings = '{}'::jsonb WHERE id = 1");
        communitySettings.evict();

        admin = saveUser(UserRole.ADMIN, null, null);
        moderator = saveUser(UserRole.CONSUMER, "mod" + suffix(), User.STAFF_MODERATOR);
        owner = saveUser(UserRole.BUSINESS_OWNER, null, null);
        member = saveUser(UserRole.CONSUMER, "mem" + suffix(), null);
        adminToken = jwtService.generateAccessToken(admin.getId(), UserRole.ADMIN);
        ownerToken = jwtService.generateAccessToken(owner.getId(), UserRole.BUSINESS_OWNER);
        memberToken = jwtService.generateAccessToken(member.getId(), UserRole.CONSUMER);
        asAdmin = user(admin.getId().toString()).roles("ADMIN");

        categoryId = UUID.randomUUID();
        jdbc.update("INSERT INTO category (id, name, kind) VALUES (?, ?, 'RESTAURANT')", categoryId, "Food P1 " + suffix());
        cityId = UUID.randomUUID();
        jdbc.update("INSERT INTO city (id, name) VALUES (?, ?)", cityId, "City P1 " + suffix());
        areaId = UUID.randomUUID();
        jdbc.update("INSERT INTO area (id, city_id, name) VALUES (?, ?, ?)", areaId, cityId, "Area P1 " + suffix());
        businessId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO business (id, owner_user_id, name, slug, category_id, city_id, area_id, contact_number, location, verified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(90.3687, 23.8069), 4326), false)
                """, businessId, owner.getId(), "Phase One Kitchen " + suffix(), "p1-" + businessId, categoryId, cityId, areaId,
                "+88017" + suffix());
    }

    // =================================================================
    // 403 for non-admins on every new endpoint
    // =================================================================

    @Test
    void nonAdminGets403OnEveryNewAdminEndpoint() throws Exception {
        UUID someId = UUID.randomUUID();
        UUID uid = member.getId();
        List<Object[]> endpoints = List.of(
                new Object[]{HttpMethod.GET, "/admin/photos"},
                new Object[]{HttpMethod.GET, "/admin/photos/" + someId + "/image"},
                new Object[]{HttpMethod.POST, "/admin/photos/" + someId + "/approve"},
                new Object[]{HttpMethod.POST, "/admin/photos/" + someId + "/reject"},
                new Object[]{HttpMethod.POST, "/admin/photos/bulk"},
                new Object[]{HttpMethod.POST, "/admin/photos/settings"},
                new Object[]{HttpMethod.GET, "/admin/settings"},
                new Object[]{HttpMethod.POST, "/admin/settings/ORDERING"},
                new Object[]{HttpMethod.GET, "/admin/users"},
                new Object[]{HttpMethod.GET, "/admin/users/" + uid + "?tab=activity"},
                new Object[]{HttpMethod.POST, "/admin/users/" + uid + "/suspend"},
                new Object[]{HttpMethod.POST, "/admin/users/" + uid + "/ban"},
                new Object[]{HttpMethod.POST, "/admin/users/" + uid + "/lift"},
                new Object[]{HttpMethod.POST, "/admin/users/" + uid + "/force-logout"},
                new Object[]{HttpMethod.POST, "/admin/users/" + uid + "/role"},
                new Object[]{HttpMethod.POST, "/admin/businesses/" + businessId + "/photos/" + someId + "/delete"},
                new Object[]{HttpMethod.POST, "/admin/businesses/" + businessId + "/photos/" + someId + "/cover"},
                new Object[]{HttpMethod.GET, "/admin/businesses/" + businessId + "/photos/" + someId + "/image"});
        RequestPostProcessor asModerator = user(moderator.getId().toString()).roles("MODERATOR");
        RequestPostProcessor asConsumer = user(member.getId().toString()).roles("CONSUMER");
        for (Object[] e : endpoints) {
            HttpMethod method = (HttpMethod) e[0];
            String url = (String) e[1];
            for (RequestPostProcessor who : List.of(asModerator, asConsumer)) {
                int status = mvc.perform(request(method, url).with(who).with(csrf())
                        .param("reason", "x").param("mode", "off").param("action", "approve")
                        .param("duration", "D1").param("role", "ADMIN")).andReturn().getResponse().getStatus();
                assertThat(status).as(method + " " + url).isEqualTo(403);
            }
            // Logged out: bounced to the login page, never served.
            int anon = mvc.perform(request(method, url).with(csrf())).andReturn().getResponse().getStatus();
            assertThat(anon).as("anonymous " + method + " " + url).isIn(302, 401, 403);
        }
        // Nothing changed for the targeted user.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_restriction WHERE user_id = ?", Long.class, uid)).isZero();
        assertThat(userRepository.findById(uid).orElseThrow().getRole()).isEqualTo(UserRole.CONSUMER);
        // The uploader's own endpoint needs a login.
        assertThat(mvc.perform(get("/api/v1/photos/mine")).andReturn().getResponse().getStatus()).isIn(401, 403);
    }

    @Test
    void adminPagesRender() throws Exception {
        UUID photoId = confirmGalleryPhoto(uploadFile("business/" + businessId + "/render.jpg"));
        for (String page : List.of("/admin/photos", "/admin/photos?status=APPROVED&source=BUSINESS_PHOTO", "/admin/settings",
                "/admin/users", "/admin/users?status=SUSPENDED&role=CONSUMER", "/admin/users/" + member.getId(),
                "/admin/users/" + member.getId() + "?tab=activity", "/admin/businesses/" + businessId)) {
            MvcResult r = mvc.perform(get(page).with(asAdmin)).andReturn();
            assertThat(r.getResponse().getStatus()).as(page + " " + r.getResponse().getErrorMessage()).isEqualTo(200);
        }
        UUID entryId = moderationEntryFor(photoId);
        assertThat(mvc.perform(get("/admin/photos/" + entryId + "/image").with(asAdmin)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        String userPage = mvc.perform(get("/admin/users/" + member.getId()).with(asAdmin)).andReturn().getResponse().getContentAsString();
        // Bug 1: the heading printed the literal text "user.phoneNumber".
        assertThat(userPage).doesNotContain("user.phoneNumber").contains(member.getPhoneNumber()).contains(member.getName());
    }

    // =================================================================
    // Photo moderation
    // =================================================================

    @Test
    void pendingGalleryPhotoIsHiddenUntilApproved() throws Exception {
        String url = uploadFile("business/" + businessId + "/gallery.jpg");
        UUID photoId = confirmGalleryPhoto(url);

        // Public reads: not there.
        assertThat(get200("/api/v1/businesses/" + businessId + "/photos", null)).doesNotContain(url);
        assertThat(get200("/api/v1/businesses/p1-" + businessId, null)).doesNotContain(url);
        assertThat(get200("/api/v1/businesses/" + businessId + "/photos", memberToken)).doesNotContain(url);
        // The owner sees it with its status; /photos/mine says "Waiting for review".
        JsonNode ownerGallery = objectMapper.readTree(get200("/api/v1/businesses/" + businessId + "/photos", ownerToken));
        assertThat(ownerGallery.get(0).get("moderationStatus").asText()).isEqualTo("PENDING");
        JsonNode mine = objectMapper.readTree(get200("/api/v1/photos/mine", ownerToken));
        assertThat(mine.get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(mine.get(0).get("label").asText()).isEqualTo("Waiting for review");

        // Admin approves (reason required).
        UUID entryId = moderationEntryFor(photoId);
        mvc.perform(post("/admin/photos/" + entryId + "/approve").with(asAdmin).with(csrf()));
        assertThat(entryStatus(entryId)).as("no reason → no change").isEqualTo("PENDING");
        mvc.perform(post("/admin/photos/" + entryId + "/approve").with(asAdmin).with(csrf()).param("reason", "Meets photo guidelines"));
        assertThat(entryStatus(entryId)).isEqualTo("APPROVED");
        assertThat(get200("/api/v1/businesses/" + businessId + "/photos", null)).contains(url);
        assertThat(get200("/api/v1/businesses/p1-" + businessId, null)).contains(url);
        assertThat(auditCount("PHOTO", entryId, "PHOTO_APPROVED")).isEqualTo(1);
    }

    @Test
    void rejectedPhotoLosesPublicFileAccessImmediately() throws Exception {
        String key = "business/" + businessId + "/reject-me.jpg";
        String url = uploadFile(key);
        UUID photoId = confirmGalleryPhoto(url);
        assertThat(fileStatus(key, ownerToken)).as("uploader sees it while pending").isEqualTo(200);

        UUID entryId = moderationEntryFor(photoId);
        mvc.perform(post("/admin/photos/bulk").with(asAdmin).with(csrf())
                .param("ids", entryId.toString()).param("action", "reject").param("reason", "Not a photo of this business"));
        assertThat(entryStatus(entryId)).isEqualTo("REJECTED");
        assertThat(mvc.perform(get("/api/v1/storage/files/" + key)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(get200("/api/v1/businesses/" + businessId + "/photos", null)).doesNotContain(url);
        JsonNode mine = objectMapper.readTree(get200("/api/v1/photos/mine", ownerToken));
        assertThat(mine.get(0).get("status").asText()).isEqualTo("REJECTED");
        assertThat(mine.get(0).get("reason").asText()).isEqualTo("Not a photo of this business");
        // Admins can still look at it.
        assertThat(mvc.perform(get("/admin/photos/" + entryId + "/image").with(asAdmin)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    /**
     * QA item 7: the public storage URL — {@code GET /api/v1/storage/files/<object key>}, e.g.
     * {@code /api/v1/storage/files/business/<businessId>/<file>} — never serves a PENDING or
     * REJECTED photo without an admin session/token: anonymous and unrelated users get 404.
     * Only the uploader (their own pending photo) and admins can fetch it.
     */
    @Test
    void publicStorageUrlOfPendingOrRejectedPhotoIsNotServed() throws Exception {
        String pendingKey = "business/" + businessId + "/pending-" + suffix() + ".jpg";
        confirmGalleryPhoto(uploadFile(pendingKey));
        assertThat(fileStatus(pendingKey, null)).as("anonymous, PENDING").isIn(403, 404);
        assertThat(fileStatus(pendingKey, memberToken)).as("other user, PENDING").isIn(403, 404);
        assertThat(fileStatus(pendingKey, ownerToken)).as("uploader, PENDING").isEqualTo(200);
        assertThat(fileStatus(pendingKey, adminToken)).as("admin, PENDING").isEqualTo(200);

        String rejectedKey = "business/" + businessId + "/rejected-" + suffix() + ".jpg";
        UUID rejectedPhoto = confirmGalleryPhoto(uploadFile(rejectedKey));
        mvc.perform(post("/admin/photos/" + moderationEntryFor(rejectedPhoto) + "/reject").with(asAdmin).with(csrf())
                .param("reason", "Inappropriate"));
        assertThat(fileStatus(rejectedKey, null)).as("anonymous, REJECTED").isIn(403, 404);
        assertThat(fileStatus(rejectedKey, memberToken)).as("other user, REJECTED").isIn(403, 404);
        assertThat(fileStatus(rejectedKey, ownerToken)).as("uploader, REJECTED").isIn(403, 404);

        // Community post + review photos follow the same rule.
        String postKey = "community/" + UUID.randomUUID() + "/p.jpg";
        call(HttpMethod.POST, "/api/v1/community/posts", memberToken, Map.of("body", "A pending community photo here",
                "postType", "DISCUSSION", "topic", "GENERAL", "imageUrls", List.of(uploadFile(postKey))));
        assertThat(fileStatus(postKey, null)).isIn(403, 404);
        assertThat(fileStatus(postKey, memberToken)).isEqualTo(200);

        // Once approved, anyone can load it; files that never went through moderation stay public.
        UUID pendingEntry = jdbc.queryForObject("SELECT id FROM photo_moderation WHERE object_key = ?", UUID.class, pendingKey);
        mvc.perform(post("/admin/photos/" + pendingEntry + "/approve").with(asAdmin).with(csrf()).param("reason", "ok"));
        assertThat(fileStatus(pendingKey, null)).isEqualTo(200);
        String unmoderated = "promo/" + UUID.randomUUID() + "/flyer.png";
        uploadFile(unmoderated);
        assertThat(fileStatus(unmoderated, null)).isEqualTo(200);
    }

    @Test
    void changeRoleOffersModeratorWithTheModeratorsPageRules() throws Exception {
        mvc.perform(post("/admin/users/" + member.getId() + "/role").with(asAdmin).with(csrf()).param("role", "MODERATOR"));
        assertThat(userRepository.findById(member.getId()).orElseThrow().isModerator()).as("no reason → no change").isFalse();

        mvc.perform(post("/admin/users/" + member.getId() + "/role").with(asAdmin).with(csrf())
                .param("role", "MODERATOR").param("reason", "Helps with the Mirpur community"));
        User updated = userRepository.findById(member.getId()).orElseThrow();
        assertThat(updated.isModerator()).isTrue();
        assertThat(updated.getRole()).as("account type unchanged").isEqualTo(UserRole.CONSUMER);
        assertThat(auditCount("STAFF_ROLE", member.getId(), "MODERATOR_ASSIGNED")).isEqualTo(1);
        assertThat(mvc.perform(get("/admin/users/" + member.getId()).with(asAdmin)).andReturn().getResponse().getContentAsString())
                .contains("MODERATOR_ASSIGNED").contains("Remove MODERATOR");

        // Same rule as the Moderators page: an admin account can't be made a moderator.
        User otherAdmin = saveUser(UserRole.ADMIN, null, null);
        mvc.perform(post("/admin/users/" + otherAdmin.getId() + "/role").with(asAdmin).with(csrf())
                .param("role", "MODERATOR").param("reason", "try"));
        assertThat(userRepository.findById(otherAdmin.getId()).orElseThrow().isModerator()).isFalse();

        mvc.perform(post("/admin/users/" + member.getId() + "/role").with(asAdmin).with(csrf())
                .param("role", "REMOVE_MODERATOR").param("reason", "Stepped down"));
        assertThat(userRepository.findById(member.getId()).orElseThrow().isModerator()).isFalse();
        assertThat(auditCount("STAFF_ROLE", member.getId(), "MODERATOR_REMOVED")).isEqualTo(1);
    }

    private int fileStatus(String key, String token) throws Exception {
        var b = get("/api/v1/storage/files/" + key);
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(b).andReturn().getResponse().getStatus();
    }

    @Test
    void newCoverAndLogoStayOffTheListingUntilApproved() throws Exception {
        String oldCover = uploadFile("business/" + businessId + "/old-cover.jpg");
        jdbc.update("UPDATE business SET cover_photo_url = ? WHERE id = ?", oldCover, businessId);
        String newCover = uploadFile("business/" + businessId + "/new-cover.jpg");
        String newLogo = uploadFile("business/" + businessId + "/logo.png");

        MvcResult r = call(HttpMethod.PUT, "/api/v1/businesses/" + businessId, ownerToken, updateBody(newCover, newLogo));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
        assertThat(body.get("coverPhotoUrl").asText()).isEqualTo(oldCover);
        assertThat(body.get("logoUrl").isNull()).isTrue();
        String publicPage = get200("/api/v1/businesses/p1-" + businessId, null);
        assertThat(publicPage).contains(oldCover).doesNotContain(newCover).doesNotContain(newLogo);

        // Re-saving the form with the old values keeps the pending upload queued (no duplicate).
        call(HttpMethod.PUT, "/api/v1/businesses/" + businessId, ownerToken, updateBody(oldCover, null));
        List<UUID> pending = jdbc.queryForList(
                "SELECT id FROM photo_moderation WHERE business_id = ? AND status = 'PENDING' AND source_type IN ('COVER','LOGO')",
                UUID.class, businessId);
        assertThat(pending).hasSize(1); // the cover; removing the logo withdrew it

        mvc.perform(post("/admin/photos/" + pending.get(0) + "/approve").with(asAdmin).with(csrf()).param("reason", "ok"));
        assertThat(jdbc.queryForObject("SELECT cover_photo_url FROM business WHERE id = ?", String.class, businessId)).isEqualTo(newCover);
        assertThat(get200("/api/v1/businesses/p1-" + businessId, null)).contains(newCover);
    }

    @Test
    void pendingMenuReviewAndCommunityPhotosNeverAppearPublicly() throws Exception {
        // Menu item
        String menuPhoto = uploadFile("menu/" + businessId + "/dish.jpg");
        MvcResult m = call(HttpMethod.POST, "/api/v1/businesses/" + businessId + "/menu-items", ownerToken,
                Map.of("name", "Kacchi", "photoUrl", menuPhoto));
        assertThat(m.getResponse().getStatus()).as(m.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(m.getResponse().getContentAsString()).doesNotContain(menuPhoto);
        assertThat(get200("/api/v1/businesses/" + businessId + "/menu-items", null)).doesNotContain(menuPhoto);

        // Review
        String reviewPhoto = uploadFile("review/" + UUID.randomUUID() + "/plate.jpg");
        MvcResult rv = call(HttpMethod.POST, "/api/v1/reviews", memberToken, Map.of(
                "businessId", businessId, "rating", 4, "content", "Great kacchi, generous portions.", "photoUrls", List.of(reviewPhoto)));
        assertThat(rv.getResponse().getStatus()).as(rv.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(get200("/api/v1/reviews/business/" + businessId, ownerToken)).doesNotContain(reviewPhoto);
        assertThat(get200("/api/v1/reviews/recent", null)).doesNotContain(reviewPhoto);

        // Community post
        String postPhoto = uploadFile("community/" + UUID.randomUUID() + "/pic.jpg");
        MvcResult p = call(HttpMethod.POST, "/api/v1/community/posts", memberToken, Map.of(
                "body", "Look at this place I found in Mirpur!", "postType", "DISCUSSION", "topic", "GENERAL",
                "imageUrls", List.of(postPhoto)));
        assertThat(p.getResponse().getStatus()).as(p.getResponse().getContentAsString()).isEqualTo(200);
        String postId = objectMapper.readTree(p.getResponse().getContentAsString()).get("id").asText();
        assertThat(p.getResponse().getContentAsString()).doesNotContain(postPhoto);
        assertThat(get200("/api/v1/community/posts/" + postId, null)).doesNotContain(postPhoto);
        assertThat(get200("/api/v1/community/posts", null)).doesNotContain(postPhoto);

        // All three are queued and listed for their uploaders.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo_moderation WHERE status = 'PENDING' AND url IN (?, ?, ?)",
                Long.class, menuPhoto, reviewPhoto, postPhoto)).isEqualTo(3);
        assertThat(get200("/api/v1/photos/mine", memberToken)).contains(reviewPhoto).contains(postPhoto);

        // Approving the post photo publishes it.
        UUID postEntry = jdbc.queryForObject("SELECT id FROM photo_moderation WHERE url = ?", UUID.class, postPhoto);
        mvc.perform(post("/admin/photos/" + postEntry + "/approve").with(asAdmin).with(csrf()).param("reason", "fine"));
        assertThat(get200("/api/v1/community/posts/" + postId, null)).contains(postPhoto);
    }

    @Test
    void withApprovalOffNewPhotosArePublicImmediatelyAndExistingOnesStayApproved() throws Exception {
        String legacy = uploadFile("business/" + businessId + "/legacy.jpg");
        jdbc.update("INSERT INTO business_photo (id, business_id, url, sort_order) VALUES (?, ?, ?, 0)", UUID.randomUUID(), businessId, legacy);
        assertThat(get200("/api/v1/businesses/" + businessId + "/photos", null)).contains(legacy);

        String reason = "launch week " + suffix();
        mvc.perform(post("/admin/photos/settings").with(asAdmin).with(csrf()).param("reason", reason));
        String fresh = uploadFile("business/" + businessId + "/fresh.jpg");
        confirmGalleryPhoto(fresh);
        assertThat(get200("/api/v1/businesses/" + businessId + "/photos", null)).contains(fresh).contains(legacy);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'PHOTO_APPROVAL_SETTING' AND reason = ?",
                Long.class, reason)).isEqualTo(1);
    }

    @Test
    void adminCanDeleteOrSetCoverFromTheBusinessPage() throws Exception {
        String a = uploadFile("business/" + businessId + "/a.jpg");
        String b = uploadFile("business/" + businessId + "/b.jpg");
        UUID photoA = confirmGalleryPhoto(a);
        UUID photoB = confirmGalleryPhoto(b);

        mvc.perform(post("/admin/businesses/" + businessId + "/photos/" + photoA + "/cover").with(asAdmin).with(csrf())
                .param("reason", "Best shot of the storefront"));
        assertThat(jdbc.queryForObject("SELECT cover_photo_url FROM business WHERE id = ?", String.class, businessId)).isEqualTo(a);
        assertThat(jdbc.queryForObject("SELECT moderation_status FROM business_photo WHERE id = ?", String.class, photoA)).isEqualTo("APPROVED");

        mvc.perform(post("/admin/businesses/" + businessId + "/photos/" + photoB + "/delete").with(asAdmin).with(csrf())
                .param("reason", "Duplicate"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_photo WHERE id = ?", Long.class, photoB)).isZero();
        assertThat(mvc.perform(get("/api/v1/storage/files/business/" + businessId + "/b.jpg")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(auditCount("BUSINESS", businessId, "BUSINESS_COVER_SET")).isEqualTo(1);
        assertThat(auditCount("BUSINESS", businessId, "BUSINESS_PHOTO_DELETED")).isEqualTo(1);
    }

    // =================================================================
    // Platform user control
    // =================================================================

    @Test
    void suspendedUserCannotLogInOrPostAndSeesReasonAndEndDate() throws Exception {
        MvcResult ok = login(member);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String refresh = objectMapper.readTree(ok.getResponse().getContentAsString()).get("refreshToken").asText();

        mvc.perform(post("/admin/users/" + member.getId() + "/suspend").with(asAdmin).with(csrf())
                .param("duration", "D7").param("reason", "Spamming review links"));

        MvcResult refused = login(member);
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        JsonNode err = objectMapper.readTree(refused.getResponse().getContentAsString());
        assertThat(err.get("code").asText()).isEqualTo("ACCOUNT_SUSPENDED");
        assertThat(err.get("reason").asText()).isEqualTo("Spamming review links");
        assertThat(err.get("endsAt").isNull()).isFalse();
        assertThat(err.get("message").asText()).contains("suspended until").contains("Spamming review links");

        // A token issued before the suspension can't write anything...
        MvcResult post = call(HttpMethod.POST, "/api/v1/community/posts", memberToken,
                Map.of("body", "Trying to post while suspended", "postType", "DISCUSSION", "topic", "GENERAL", "imageUrls", List.of()));
        assertThat(post.getResponse().getStatus()).isEqualTo(403);
        assertThat(post.getResponse().getContentAsString()).contains("ACCOUNT_SUSPENDED");
        MvcResult review = call(HttpMethod.POST, "/api/v1/reviews", memberToken,
                Map.of("businessId", businessId, "rating", 1, "content", "Suspended users cannot review."));
        assertThat(review.getResponse().getStatus()).isEqualTo(403);
        // ...its session can't be refreshed (refresh tokens were revoked)...
        assertThat(call(HttpMethod.POST, "/api/v1/auth/refresh", null, Map.of("refreshToken", refresh)).getResponse().getStatus()).isEqualTo(403);
        // ...but reads still work.
        assertThat(call(HttpMethod.GET, "/api/v1/community/posts", memberToken, null).getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_login_event WHERE user_id = ? AND outcome = 'RESTRICTED'",
                Long.class, member.getId())).isEqualTo(1);
        assertThat(auditCount("USER", member.getId(), "USER_SUSPENDED")).isEqualTo(1);

        // The list filter finds them.
        String suspendedList = mvc.perform(get("/admin/users").param("status", "SUSPENDED").param("query", member.getPhoneNumber())
                .with(asAdmin)).andReturn().getResponse().getContentAsString();
        // (the search box echoes the query, so look for the row link, not the phone)
        assertThat(suspendedList).contains("/admin/users/" + member.getId());
        String activeList = mvc.perform(get("/admin/users").param("status", "ACTIVE").param("query", member.getPhoneNumber())
                .with(asAdmin)).andReturn().getResponse().getContentAsString();
        assertThat(activeList).doesNotContain("/admin/users/" + member.getId());

        // Lift → can log in again.
        mvc.perform(post("/admin/users/" + member.getId() + "/lift").with(asAdmin).with(csrf()).param("reason", "Appeal accepted"));
        assertThat(login(member).getResponse().getStatus()).isEqualTo(200);
        assertThat(auditCount("USER", member.getId(), "USER_RESTRICTION_LIFTED")).isEqualTo(1);
    }

    @Test
    void banForceLogoutAndRoleChange() throws Exception {
        String refresh = objectMapper.readTree(login(member).getResponse().getContentAsString()).get("refreshToken").asText();
        mvc.perform(post("/admin/users/" + member.getId() + "/force-logout").with(asAdmin).with(csrf()).param("reason", "Lost phone"));
        assertThat(call(HttpMethod.POST, "/api/v1/auth/refresh", null, Map.of("refreshToken", refresh)).getResponse().getStatus()).isEqualTo(403);
        assertThat(auditCount("USER", member.getId(), "USER_FORCE_LOGOUT")).isEqualTo(1);

        mvc.perform(post("/admin/users/" + member.getId() + "/role").with(asAdmin).with(csrf()).param("role", "BUSINESS_OWNER")
                .param("reason", "Asked to switch to a business account"));
        assertThat(userRepository.findById(member.getId()).orElseThrow().getRole()).isEqualTo(UserRole.BUSINESS_OWNER);
        assertThat(auditCount("USER", member.getId(), "USER_ROLE_CHANGED")).isEqualTo(1);

        mvc.perform(post("/admin/users/" + member.getId() + "/ban").with(asAdmin).with(csrf()).param("reason", "Fraud"));
        MvcResult refused = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("phoneNumber", member.getPhoneNumber(), "password", PASSWORD,
                        "context", "BUSINESS_OWNER")))).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(objectMapper.readTree(refused.getResponse().getContentAsString()).get("code").asText()).isEqualTo("ACCOUNT_BANNED");

        // Every action needs a reason; an admin can't act on their own account.
        mvc.perform(post("/admin/users/" + owner.getId() + "/ban").with(asAdmin).with(csrf()).param("reason", " "));
        mvc.perform(post("/admin/users/" + admin.getId() + "/suspend").with(asAdmin).with(csrf()).param("duration", "D1").param("reason", "self"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_restriction WHERE user_id IN (?, ?)", Long.class,
                owner.getId(), admin.getId())).isZero();
    }

    // =================================================================
    // Feature flags
    // =================================================================

    @Test
    void eachFlagOffReallyDisablesItsEndpoints() throws Exception {
        record Probe(String flag, HttpMethod method, String url, String token, Object body) {
        }
        List<Probe> probes = List.of(
                new Probe("ORDERING", HttpMethod.GET, "/api/v1/orders/mine", memberToken, null),
                new Probe("ORDERING", HttpMethod.POST, "/api/v1/businesses/" + businessId + "/orders", memberToken, Map.of()),
                new Probe("BOOKINGS", HttpMethod.GET, "/api/v1/businesses/" + businessId + "/bookings/availability?date=2030-01-01", null, null),
                new Probe("BOOKINGS", HttpMethod.GET, "/api/v1/bookings/mine", memberToken, null),
                new Probe("COMMUNITY", HttpMethod.GET, "/api/v1/community/posts", null, null),
                new Probe("COMMUNITY", HttpMethod.GET, "/api/v1/community/search?q=kacchi", null, null),
                new Probe("PROMOTIONS", HttpMethod.GET, "/api/v1/promo/public/featured-nearby", null, null),
                new Probe("PROMOTIONS", HttpMethod.GET, "/api/v1/promo/boost-packages", ownerToken, null),
                new Probe("OWNER_CHAT", HttpMethod.GET, "/api/v1/messages/threads/mine", memberToken, null),
                new Probe("OWNER_CHAT", HttpMethod.GET, "/api/v1/messages/threads/business-inbox", ownerToken, null),
                new Probe("NEW_SIGNUPS", HttpMethod.POST, "/api/v1/auth/register", null,
                        Map.of("phoneNumber", "01712345678", "code", "123456", "password", "password1", "role", "CONSUMER", "name", "X")));

        Map<String, String> publicKey = Map.of("ORDERING", "orderingEnabled", "BOOKINGS", "bookingsEnabled",
                "COMMUNITY", "communityEnabled", "PROMOTIONS", "promotionsEnabled", "OWNER_CHAT", "ownerChatEnabled",
                "NEW_SIGNUPS", "newSignupsEnabled");

        // Baseline: every flag on → no "disabled" answers.
        for (Probe p : probes) {
            MvcResult r = call(p.method(), p.url(), p.token(), p.body());
            assertThat(r.getResponse().getContentAsString()).as("baseline " + p).doesNotContain("is currently disabled");
        }

        for (String flag : publicKey.keySet()) {
            setFlag(flag, "off");
            JsonNode settings = objectMapper.readTree(get200("/api/v1/community/settings", null));
            assertThat(settings.get("features").get(publicKey.get(flag)).asBoolean()).as(flag + " public").isFalse();
            for (Probe p : probes) {
                MvcResult r = call(p.method(), p.url(), p.token(), p.body());
                if (p.flag().equals(flag)) {
                    assertThat(r.getResponse().getStatus()).as(flag + " off: " + p.method() + " " + p.url()).isEqualTo(404);
                    assertThat(r.getResponse().getContentAsString()).contains("is currently disabled");
                } else {
                    assertThat(r.getResponse().getContentAsString()).as(flag + " off must not affect " + p.url())
                            .doesNotContain("is currently disabled");
                }
            }
            setFlag(flag, "default");
        }
        // The public settings endpoint itself stays up with community off.
        setFlag("COMMUNITY", "off");
        JsonNode settings = objectMapper.readTree(get200("/api/v1/community/settings", null));
        assertThat(settings.get("communityEnabled").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'FEATURE_FLAG_COMMUNITY'", Long.class)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void nidFlagIsNowAPlatformSetting() throws Exception {
        String key = "nid/" + UUID.randomUUID() + "/card.jpg";
        assertThat(mvc.perform(put("/api/v1/storage/upload/" + key).content(new byte[]{1})).andReturn().getResponse().getStatus()).isEqualTo(404);
        setFlag("NID_VERIFICATION", "on");
        assertThat(mvc.perform(put(signedUpload(key)).content(new byte[]{1})).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(get200("/api/v1/community/settings", null)).get("features").get("nidVerificationEnabled").asBoolean()).isTrue();
    }

    @Test
    void maintenanceModeBlocksEveryoneButAdminsAndShowsTheMessage() throws Exception {
        mvc.perform(post("/admin/settings/MAINTENANCE_MODE").with(asAdmin).with(csrf())
                .param("mode", "on").param("reason", "DB upgrade").param("message", "Back at 3pm"));
        MvcResult blocked = call(HttpMethod.GET, "/api/v1/categories", null, null);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(503);
        assertThat(blocked.getResponse().getContentAsString()).contains("Back at 3pm").contains("MAINTENANCE");
        assertThat(call(HttpMethod.GET, "/api/v1/categories", memberToken, null).getResponse().getStatus()).isEqualTo(503);
        assertThat(call(HttpMethod.GET, "/api/v1/categories", adminToken, null).getResponse().getStatus()).isEqualTo(200);
        JsonNode settings = objectMapper.readTree(get200("/api/v1/community/settings", null));
        assertThat(settings.get("features").get("maintenanceMode").asBoolean()).isTrue();
        assertThat(settings.get("features").get("maintenanceMessage").asText()).isEqualTo("Back at 3pm");
        // Login stays reachable (so admins can sign in).
        assertThat(login(member).getResponse().getStatus()).isEqualTo(200);

        mvc.perform(post("/admin/settings/MAINTENANCE_MODE").with(asAdmin).with(csrf()).param("mode", "default").param("reason", "done"));
        assertThat(call(HttpMethod.GET, "/api/v1/categories", null, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void flagChangeNeedsAReason() throws Exception {
        mvc.perform(post("/admin/settings/ORDERING").with(asAdmin).with(csrf()).param("mode", "off"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_setting WHERE setting_key = 'ORDERING'", Long.class)).isZero();
    }

    // =================================================================
    // helpers
    // =================================================================

    private void setFlag(String flag, String mode) throws Exception {
        int status = mvc.perform(post("/admin/settings/" + flag).with(asAdmin).with(csrf())
                .param("mode", mode).param("reason", "integration test")).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(302);
    }

    private String uploadFile(String key) throws Exception {
        int status = mvc.perform(put(signedUpload(key)).content(new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3}))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        return FILES + key;
    }

    private UUID confirmGalleryPhoto(String url) throws Exception {
        MvcResult r = call(HttpMethod.POST, "/api/v1/businesses/" + businessId + "/photos/confirm", ownerToken,
                Map.of("businessId", businessId, "cdnUrl", url));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return UUID.fromString(objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID moderationEntryFor(UUID businessPhotoId) {
        String url = jdbc.queryForObject("SELECT url FROM business_photo WHERE id = ?", String.class, businessPhotoId);
        return jdbc.queryForObject("SELECT id FROM photo_moderation WHERE source_type = 'BUSINESS_PHOTO' AND url = ?", UUID.class, url);
    }

    private String entryStatus(UUID entryId) {
        return jdbc.queryForObject("SELECT status FROM photo_moderation WHERE id = ?", String.class, entryId);
    }

    private long auditCount(String entityType, UUID entityId, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_type = ? AND entity_id = ? AND action = ?",
                Long.class, entityType, entityId, action);
    }

    private Map<String, Object> updateBody(String cover, String logo) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "Phase One Kitchen");
        m.put("categoryId", categoryId);
        m.put("cityId", cityId);
        m.put("areaId", areaId);
        m.put("contactNumber", "01711111111");
        m.put("coverPhotoUrl", cover);
        m.put("logoUrl", logo);
        m.put("latitude", 23.8);
        m.put("longitude", 90.36);
        m.put("priceTier", "MODERATE");
        m.put("attributeIds", new ArrayList<>());
        return m;
    }

    private MvcResult login(User u) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("phoneNumber", u.getPhoneNumber(), "password", PASSWORD)))).andReturn();
    }

    private String get200(String url, String token) throws Exception {
        MvcResult r = call(HttpMethod.GET, url, token, null);
        assertThat(r.getResponse().getStatus()).as(url + " → " + r.getResponse().getContentAsString()).isEqualTo(200);
        return r.getResponse().getContentAsString();
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

    private static String suffix() {
        String n = String.valueOf(System.nanoTime());
        return n.substring(n.length() - 8);
    }

    private User saveUser(UserRole role, String communityUsername, String staffRole) {
        return userRepository.save(User.builder()
                .phoneNumber("+8801" + suffix().substring(0, 8) + (int) (Math.random() * 10))
                .role(role)
                .name(role + " P1 tester")
                .otpVerified(true)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .communityUsername(communityUsername == null ? null : communityUsername.substring(0, Math.min(20, communityUsername.length())))
                .communityGender(communityUsername == null ? null : "M")
                .staffRole(staffRole)
                .build());
    }

    /** Upload URLs must carry the API's signature (StorageUrlSigner), exactly as the app receives them. */
    private String signedUpload(String key) {
        String url = storageUrls.uploadUrl(key);
        return url.substring(url.indexOf("/api/v1/storage/upload/"));
    }
}

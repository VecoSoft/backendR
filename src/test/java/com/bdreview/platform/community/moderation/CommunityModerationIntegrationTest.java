package com.bdreview.platform.community.moderation;

import com.bdreview.platform.auth.JwtService;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.community.settings.CommunitySettingsService;
import com.bdreview.platform.moderation.AuditLogRepository;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks for the community moderation & control system, through the real HTTP layer
 * (security filter chains, @PreAuthorize, controllers, services, Flyway-migrated schema).
 *
 * <p>Runs against a throwaway database {@code bd_review_it} on the local Postgres (created with
 * {@code CREATE DATABASE bd_review_it}; Flyway migrates it from V1) — never the dev database.
 * Redis is optional: the settings cache falls back to the database when it's unreachable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/bd_review_it",
        "features.nid-verification.enabled=false",
        "app.admin.bootstrap-enabled=false",
        "app.storage.local-dir=${java.io.tmpdir}/bd-review-it-uploads"
})
class CommunityModerationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired com.bdreview.platform.gallery.StorageUrlSigner storageUrls;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired CommunitySettingsService settingsService;
    @Autowired com.bdreview.platform.features.PlatformSettingStore platformSettings;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired ObjectMapper objectMapper;

    User admin;
    User moderator;
    User member;
    User other;
    String adminToken;
    String moderatorToken;
    String memberToken;
    String otherToken;

    @BeforeEach
    void setUp() {
        // Every test starts from default settings (and a cold cache).
        jdbc.update("UPDATE community_settings SET settings = '{}'::jsonb WHERE id = 1");
        settingsService.evict();
        jdbc.update("DELETE FROM platform_setting");
        platformSettings.evict();

        admin = saveUser(UserRole.ADMIN, null, null);
        moderator = saveUser(UserRole.CONSUMER, "mod" + suffix(), User.STAFF_MODERATOR);
        member = saveUser(UserRole.CONSUMER, "member" + suffix(), null);
        other = saveUser(UserRole.CONSUMER, "other" + suffix(), null);
        adminToken = jwtService.generateAccessToken(admin.getId(), UserRole.ADMIN);
        moderatorToken = jwtService.generateAccessToken(moderator.getId(), UserRole.CONSUMER);
        memberToken = jwtService.generateAccessToken(member.getId(), UserRole.CONSUMER);
        otherToken = jwtService.generateAccessToken(other.getId(), UserRole.CONSUMER);
    }

    private static String suffix() {
        String n = String.valueOf(System.nanoTime());
        return n.substring(n.length() - 8);
    }

    private User saveUser(UserRole role, String communityUsername, String staffRole) {
        return userRepository.save(User.builder()
                .phoneNumber("+8801" + suffix().substring(0, 8) + (int) (Math.random() * 10))
                .email(java.util.UUID.randomUUID() + "@it.jachai.test").emailVerifiedAt(java.time.Instant.now())
                .role(role)
                .name(role + " tester")
                .otpVerified(true)
                .passwordHash("$2a$10$abcdefghijklmnopqrstuuJ4t6x0bE2Qm0vXgk3H5yqk6n2k1V0bW")
                .communityUsername(communityUsername == null ? null : communityUsername.substring(0, Math.min(20, communityUsername.length())))
                .communityGender(communityUsername == null ? null : "F")
                .staffRole(staffRole)
                .build());
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    private MvcResult call(HttpMethod method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder b = request(method, url).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            b.content(objectMapper.writeValueAsString(body));
        }
        return mvc.perform(auth(b, token)).andReturn();
    }

    private UUID createPost(String token, String body, String type) throws Exception {
        MvcResult r = call(HttpMethod.POST, "/api/v1/community/posts", token,
                Map.of("body", body, "postType", type, "topic", "GENERAL", "imageUrls", List.of()));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return UUID.fromString(objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    private void putSettings(Map<String, Object> patch) throws Exception {
        JsonNode current = objectMapper.readTree(call(HttpMethod.GET, "/api/v1/admin/community/settings", adminToken, null)
                .getResponse().getContentAsString());
        JsonNode merged = objectMapper.readerForUpdating(current).readValue(objectMapper.writeValueAsString(patch));
        MvcResult r = call(HttpMethod.PUT, "/api/v1/admin/community/settings", adminToken, merged);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
    }

    // -----------------------------------------------------------------
    // Roles
    // -----------------------------------------------------------------

    @Test
    void nonStaffGets403OnEveryAdminEndpoint() throws Exception {
        UUID someId = UUID.randomUUID();
        List<Object[]> endpoints = List.of(
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/dashboard"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/posts"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/posts/" + someId},
                new Object[]{HttpMethod.POST, "/api/v1/admin/community/posts/" + someId + "/remove"},
                new Object[]{HttpMethod.POST, "/api/v1/admin/community/posts/bulk"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/comments"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/reports"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/queue"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/users"},
                new Object[]{HttpMethod.POST, "/api/v1/admin/community/users/" + someId + "/restrictions"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/settings"},
                new Object[]{HttpMethod.PUT, "/api/v1/admin/community/settings"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/topics"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/announcements"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/audit"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/roles"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/moderation/summary"},
                // pre-existing admin endpoints that only had a service-level check before
                new Object[]{HttpMethod.GET, "/api/v1/claims/queue"},
                new Object[]{HttpMethod.POST, "/api/v1/claims/" + someId + "/resolve"},
                new Object[]{HttpMethod.GET, "/api/v1/reports/queue"},
                new Object[]{HttpMethod.POST, "/api/v1/reports/" + someId + "/resolve"},
                new Object[]{HttpMethod.GET, "/api/v1/offers/admin/queue"},
                new Object[]{HttpMethod.POST, "/api/v1/offers/admin/" + someId + "/approve"});
        for (Object[] e : endpoints) {
            for (String token : new String[]{memberToken, null}) {
                // A body that passes every endpoint's @Valid check, so the role check is what answers.
                MvcResult r = call((HttpMethod) e[0], (String) e[1], token,
                        Map.of("approve", true, "outcome", "DISMISSED", "reason", "x", "type", "WARN"));
                assertThat(r.getResponse().getStatus()).as(e[0] + " " + e[1] + (token == null ? " (anonymous)" : " (member)"))
                        .isIn(401, 403);
            }
        }
        // Session admin panel: a non-logged-in visitor is bounced to the login page.
        mvc.perform(get("/admin/community")).andExpect(status().is3xxRedirection());
    }

    @Test
    void moderatorCanModerateButNotChangeSettingsRolesOrRevealIdentity() throws Exception {
        assertThat(call(HttpMethod.GET, "/api/v1/admin/community/posts", moderatorToken, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/community/reports", moderatorToken, null).getResponse().getStatus()).isEqualTo(200);

        List<Object[]> adminOnly = List.of(
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/settings"},
                new Object[]{HttpMethod.PUT, "/api/v1/admin/community/settings"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/roles"},
                new Object[]{HttpMethod.POST, "/api/v1/admin/community/roles/moderators"},
                new Object[]{HttpMethod.POST, "/api/v1/admin/community/users/" + member.getId() + "/reveal"},
                new Object[]{HttpMethod.POST, "/api/v1/admin/community/topics"},
                new Object[]{HttpMethod.POST, "/api/v1/admin/community/announcements"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/community/audit"});
        for (Object[] e : adminOnly) {
            MvcResult r = call((HttpMethod) e[0], (String) e[1], moderatorToken, Map.of("reason", "x"));
            assertThat(r.getResponse().getStatus()).as("MODERATOR " + e[0] + " " + e[1]).isEqualTo(403);
        }

        // ...and removing the role is immediate (resolved from the DB on every admin API call).
        call(HttpMethod.DELETE, "/api/v1/admin/community/roles/moderators/" + moderator.getId(), adminToken, null);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/community/posts", moderatorToken, null).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void revealIdentityIsAdminOnlyNeedsAReasonAndIsAudited() throws Exception {
        assertThat(call(HttpMethod.POST, "/api/v1/admin/community/users/" + member.getId() + "/reveal", adminToken,
                Map.of("reason", "")).getResponse().getStatus()).isEqualTo(400);
        MvcResult r = call(HttpMethod.POST, "/api/v1/admin/community/users/" + member.getId() + "/reveal", adminToken,
                Map.of("reason", "Police request #123"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentAsString()).contains(member.getPhoneNumber());
        assertThat(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("COMMUNITY_USER", member.getId(),
                org.springframework.data.domain.PageRequest.of(0, 5)).getContent())
                .anyMatch(a -> "IDENTITY_REVEALED".equals(a.getAction()) && "ADMIN".equals(a.getActorRole())
                        && "Police request #123".equals(a.getReason()));
    }

    // -----------------------------------------------------------------
    // Restrictions
    // -----------------------------------------------------------------

    @Test
    void mutedUserGets403OnEveryCommunityWrite() throws Exception {
        UUID postId = createPost(otherToken, "A perfectly normal post about biryani in Dhanmondi.", "DISCUSSION");

        MvcResult mute = call(HttpMethod.POST, "/api/v1/admin/community/users/" + member.getId() + "/restrictions", moderatorToken,
                Map.of("type", "MUTE", "duration", "H24", "reason", "Spamming links"));
        assertThat(mute.getResponse().getStatus()).as(mute.getResponse().getContentAsString()).isEqualTo(200);

        MvcResult post = call(HttpMethod.POST, "/api/v1/community/posts", memberToken,
                Map.of("body", "trying to post while muted", "postType", "DISCUSSION", "topic", "GENERAL"));
        assertThat(post.getResponse().getStatus()).isEqualTo(403);
        assertThat(post.getResponse().getContentAsString()).contains("muted").contains("Spamming links");

        assertThat(call(HttpMethod.POST, "/api/v1/community/posts/" + postId + "/comments", memberToken,
                Map.of("content", "a comment")).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/api/v1/community/posts/" + postId + "/votes", memberToken,
                Map.of("voteType", "UPVOTE")).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/api/v1/reports", memberToken,
                Map.of("targetType", "COMMUNITY_POST", "targetId", postId, "reason", "SPAM")).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/api/v1/community/users/" + other.getCommunityProfileId() + "/follow", memberToken, null)
                .getResponse().getStatus()).isEqualTo(403);

        // The member sees the notice via their standing.
        mvc.perform(get("/api/v1/community/me/standing").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(true))
                .andExpect(jsonPath("$.restriction.type").value("MUTE"));

        // Everyone else is unaffected.
        assertThat(call(HttpMethod.POST, "/api/v1/community/posts/" + postId + "/votes", otherToken,
                Map.of("voteType", "UPVOTE")).getResponse().getStatus()).isEqualTo(204);
    }

    // -----------------------------------------------------------------
    // Settings apply on the next request
    // -----------------------------------------------------------------

    @Test
    void changingASettingAppliesOnTheNextRequestWithoutRestart() throws Exception {
        createPost(memberToken, "Short but fine", "DISCUSSION");

        putSettings(Map.of("content", Map.of("postBodyMin", 50)));
        MvcResult tooShort = call(HttpMethod.POST, "/api/v1/community/posts", memberToken,
                Map.of("body", "Short but fine", "postType", "DISCUSSION", "topic", "GENERAL"));
        assertThat(tooShort.getResponse().getStatus()).isEqualTo(400);
        assertThat(tooShort.getResponse().getContentAsString()).contains("at least 50");

        putSettings(Map.of("postTypes", Map.of("questionEnabled", false)));
        MvcResult question = call(HttpMethod.POST, "/api/v1/community/posts", memberToken,
                Map.of("body", "Where can I find the best kacchi biryani near Mohammadpur these days?", "postType", "QUESTION", "topic", "GENERAL"));
        assertThat(question.getResponse().getStatus()).isEqualTo(400);
        assertThat(question.getResponse().getContentAsString()).contains("turned off");

        // The public settings endpoint reflects it too (what the Next.js app reads).
        mvc.perform(get("/api/v1/community/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postTypes.QUESTION").value(false))
                .andExpect(jsonPath("$.limits.postBodyMin").value(50));

        // Every save is audited with a before/after diff.
        assertThat(auditLogRepository.findAll()).anyMatch(a -> "SETTINGS_UPDATED".equals(a.getAction())
                && a.getBeforeJson() != null && a.getBeforeJson().contains("content.postBodyMin"));
    }

    @Test
    void maintenanceModeBlocksReadsAndWritesButNotSettings() throws Exception {
        putSettings(Map.of("general", Map.of("communityEnabled", false, "maintenanceMessage", "Back at 9pm")));
        MvcResult feed = call(HttpMethod.GET, "/api/v1/community/posts", memberToken, null);
        assertThat(feed.getResponse().getStatus()).isEqualTo(503);
        assertThat(feed.getResponse().getContentAsString()).contains("Back at 9pm");
        mvc.perform(get("/api/v1/community/settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.communityEnabled").value(false));
    }

    // -----------------------------------------------------------------
    // Remove / restore keeps comments + votes; every action is audited
    // -----------------------------------------------------------------

    @Test
    void removeAndRestoreKeepCommentsAndVotesAndWriteAuditRows() throws Exception {
        UUID postId = createPost(memberToken, "My honest review of the new cafe on Road 27.", "DISCUSSION");
        assertThat(call(HttpMethod.POST, "/api/v1/community/posts/" + postId + "/comments", otherToken,
                Map.of("content", "Thanks for sharing!")).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/v1/community/posts/" + postId + "/votes", otherToken,
                Map.of("voteType", "UPVOTE")).getResponse().getStatus()).isEqualTo(204);

        assertThat(call(HttpMethod.POST, "/api/v1/admin/community/posts/" + postId + "/remove", moderatorToken,
                Map.of("reason", "")).getResponse().getStatus()).isEqualTo(400); // reason required
        assertThat(call(HttpMethod.POST, "/api/v1/admin/community/posts/" + postId + "/remove", moderatorToken,
                Map.of("reason", "Off-topic")).getResponse().getStatus()).isEqualTo(204);

        // Others: placeholder (no content), thread + votes intact.
        JsonNode removed = objectMapper.readTree(call(HttpMethod.GET, "/api/v1/community/posts/" + postId, otherToken, null)
                .getResponse().getContentAsString());
        assertThat(removed.get("status").asText()).isEqualTo("REMOVED");
        assertThat(removed.get("body").isNull()).isTrue();
        assertThat(removed.get("removedReason").isNull()).isTrue();
        assertThat(removed.get("upvoteCount").asInt()).isEqualTo(1);
        assertThat(removed.get("commentCount").asInt()).isEqualTo(1);
        // The author sees the reason.
        JsonNode forAuthor = objectMapper.readTree(call(HttpMethod.GET, "/api/v1/community/posts/" + postId, memberToken, null)
                .getResponse().getContentAsString());
        assertThat(forAuthor.get("removedReason").asText()).isEqualTo("Off-topic");
        JsonNode comments = objectMapper.readTree(call(HttpMethod.GET, "/api/v1/community/posts/" + postId + "/comments", otherToken, null)
                .getResponse().getContentAsString());
        assertThat(comments.get("content")).hasSize(1);
        // Removed posts leave the feed.
        assertThat(call(HttpMethod.GET, "/api/v1/community/posts?size=30", otherToken, null).getResponse().getContentAsString())
                .doesNotContain(postId.toString());

        assertThat(call(HttpMethod.POST, "/api/v1/admin/community/posts/" + postId + "/restore", moderatorToken,
                Map.of("reason", "Appeal accepted")).getResponse().getStatus()).isEqualTo(204);
        JsonNode restored = objectMapper.readTree(call(HttpMethod.GET, "/api/v1/community/posts/" + postId, otherToken, null)
                .getResponse().getContentAsString());
        assertThat(restored.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(restored.get("body").asText()).contains("Road 27");
        assertThat(restored.get("upvoteCount").asInt()).isEqualTo(1);
        assertThat(restored.get("commentCount").asInt()).isEqualTo(1);

        var audit = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("COMMUNITY_POST", postId,
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertThat(audit).anyMatch(a -> a.getAction().equals("POST_REMOVED") && "MODERATOR".equals(a.getActorRole())
                && "Off-topic".equals(a.getReason()) && a.getBeforeJson().contains("ACTIVE") && a.getAfterJson().contains("REMOVED"));
        assertThat(audit).anyMatch(a -> a.getAction().equals("POST_RESTORED"));
    }

    @Test
    void lockedPostRejectsNewComments() throws Exception {
        UUID postId = createPost(memberToken, "Locking this one after the discussion ran its course.", "DISCUSSION");
        assertThat(call(HttpMethod.POST, "/api/v1/admin/community/posts/" + postId + "/lock", moderatorToken,
                Map.of("reason", "Heated thread")).getResponse().getStatus()).isEqualTo(204);
        MvcResult r = call(HttpMethod.POST, "/api/v1/community/posts/" + postId + "/comments", otherToken, Map.of("content", "one more"));
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("Comments are turned off");
    }

    // -----------------------------------------------------------------
    // Admin panel (Thymeleaf) — every Community page renders; moderator is kept out of admin-only ones
    // -----------------------------------------------------------------

    @Test
    void adminPanelCommunityPagesRender() throws Exception {
        UUID postId = createPost(memberToken, "A post the admin panel can open and render.", "DISCUSSION");
        call(HttpMethod.POST, "/api/v1/community/posts/" + postId + "/comments", otherToken, Map.of("content", "hello"));
        call(HttpMethod.POST, "/api/v1/reports", otherToken, Map.of("targetType", "COMMUNITY_POST", "targetId", postId, "reason", "SPAM"));
        var adminSession = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user(admin.getId().toString()).roles("ADMIN");
        var modSession = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user(moderator.getId().toString()).roles("MODERATOR");
        for (String page : List.of("/admin/community", "/admin/community/posts", "/admin/community/posts?status=ACTIVE&sort=reported",
                "/admin/community/posts/" + postId, "/admin/community/comments", "/admin/community/reports",
                "/admin/community/pending", "/admin/community/users", "/admin/community/users/" + member.getId(),
                "/admin/community/settings", "/admin/community/announcements", "/admin/community/roles",
                "/admin/community/audit", "/admin/community/audit.csv")) {
            MvcResult r = mvc.perform(get(page).with(adminSession)).andReturn();
            assertThat(r.getResponse().getStatus()).as("ADMIN " + page).isEqualTo(200);
        }
        assertThat(mvc.perform(get("/admin/community/posts").with(modSession)).andReturn().getResponse().getStatus()).isEqualTo(200);
        for (String page : List.of("/admin/community/settings", "/admin/community/roles", "/admin/community/audit", "/admin/dashboard")) {
            int s = mvc.perform(get(page).with(modSession)).andReturn().getResponse().getStatus();
            assertThat(s).as("MODERATOR " + page).isNotEqualTo(200);
        }
        // A settings form round-trip through the panel (CSRF + binding) saves and is audited.
        mvc.perform(post("/admin/community/settings").with(adminSession)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("general.communityEnabled", "true").param("_general.communityEnabled", "on")
                        .param("content.postBodyMin", "3").param("content.postBodyMax", "5000")
                        .param("content.commentMax", "2000").param("content.questionTitleMax", "150")
                        .param("content.bannedWordsMode", "FLAG").param("bannedWordsText", "spamword\nখারাপ")
                        .param("postTypes.discussionEnabled", "true").param("postTypes.maxImagesPerPost", "4")
                        .param("postTypes.maxImageSizeMb", "5").param("nidMode", ""))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/v1/community/settings")).andExpect(jsonPath("$.maxImagesPerPost").value(4))
                .andExpect(jsonPath("$.limits.postBodyMin").value(3));
    }

    // -----------------------------------------------------------------
    // NID feature flag
    // -----------------------------------------------------------------

    @Test
    void nidFlagOffDisablesNidAndCommunityWorksWithoutIt() throws Exception {
        mvc.perform(get("/api/v1/community/settings")).andExpect(jsonPath("$.features.nidVerificationEnabled").value(false));

        // Upload endpoint for NID files: 404 "Feature disabled", nothing stored.
        MvcResult upload = mvc.perform(put("/api/v1/storage/upload/nid/" + UUID.randomUUID() + "/card.jpg")
                .content(new byte[]{1, 2, 3})).andReturn();
        assertThat(upload.getResponse().getStatus()).isEqualTo(404);
        assertThat(upload.getResponse().getContentAsString()).contains("Feature disabled");
        // Stored NID files are never public; even an admin gets 404 while the flag is off.
        assertThat(mvc.perform(get("/api/v1/storage/files/nid/x/card.jpg")).andReturn().getResponse().getStatus()).isIn(401, 403);
        assertThat(mvc.perform(get("/api/v1/storage/files/nid/x/card.jpg").header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        // No flow requires NID: a fresh, never-NID-verified member can post, comment and vote.
        UUID postId = createPost(memberToken, "Posting without any NID verification at all.", "DISCUSSION");
        assertThat(call(HttpMethod.POST, "/api/v1/community/posts/" + postId + "/comments", otherToken,
                Map.of("content", "Same here")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void nidFlagOnRestoresNidUploadsForAdmins() throws Exception {
        // V63: the NID flag moved from the community settings document to System → Settings (platform_setting).
        mvc.perform(post("/admin/settings/NID_VERIFICATION")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(admin.getId().toString()).roles("ADMIN"))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .param("mode", "on").param("reason", "NID test"));
        String key = "nid/" + UUID.randomUUID() + "/card.jpg";
        assertThat(mvc.perform(put(signedUpload(key)).content(new byte[]{1, 2, 3}))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/api/v1/storage/files/" + key).header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        // ...but still never public.
        assertThat(mvc.perform(get("/api/v1/storage/files/" + key)).andReturn().getResponse().getStatus()).isIn(401, 403);
        mvc.perform(get("/api/v1/community/settings")).andExpect(jsonPath("$.features.nidVerificationEnabled").value(true));
    }

    /** Upload URLs must carry the API's signature (StorageUrlSigner), exactly as the app receives them. */
    private String signedUpload(String key) {
        String url = storageUrls.uploadUrl(key);
        return url.substring(url.indexOf("/api/v1/storage/upload/"));
    }
}

package com.bdreview.platform.promo;

import com.bdreview.platform.auth.JwtService;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.community.settings.CommunitySettingsService;
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

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * End-to-end checks for business promotion (V58) through the real HTTP layer, security and schema,
 * on the throwaway {@code bd_review_it} database (never the dev database). The ML service URL points
 * at a closed port, so caption generation must fall back to templates.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/bd_review_it",
        "features.nid-verification.enabled=false",
        "app.admin.bootstrap-enabled=false",
        "app.storage.local-dir=${java.io.tmpdir}/bd-review-it-uploads",
        "app.ml-service.base-url=http://localhost:1"
})
class PromotionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redisTemplates;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired CommunitySettingsService settingsService;
    @Autowired ObjectMapper objectMapper;
    @Autowired PromoExpiryJob expiryJob;
    @Autowired SponsoredService sponsoredService;

    User admin;
    User moderator;
    User member;
    User owner;
    User otherOwner;
    String adminToken;
    String moderatorToken;
    String memberToken;
    String ownerToken;
    String otherOwnerToken;
    UUID businessId;
    UUID offerId;
    UUID cityId;
    UUID areaId;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE community_settings SET settings = '{}'::jsonb WHERE id = 1");
        settingsService.evict();
        // Other tests' live boosts must not interfere with slot counting.
        jdbc.update("UPDATE boost SET status = 'ENDED' WHERE status = 'ACTIVE'");

        admin = saveUser(UserRole.ADMIN, null, null);
        moderator = saveUser(UserRole.CONSUMER, "mod" + suffix(), User.STAFF_MODERATOR);
        member = saveUser(UserRole.CONSUMER, "mem" + suffix(), null);
        owner = saveUser(UserRole.BUSINESS_OWNER, null, null);
        otherOwner = saveUser(UserRole.BUSINESS_OWNER, null, null);
        adminToken = jwtService.generateAccessToken(admin.getId(), UserRole.ADMIN);
        moderatorToken = jwtService.generateAccessToken(moderator.getId(), UserRole.CONSUMER);
        memberToken = jwtService.generateAccessToken(member.getId(), UserRole.CONSUMER);
        ownerToken = jwtService.generateAccessToken(owner.getId(), UserRole.BUSINESS_OWNER);
        otherOwnerToken = jwtService.generateAccessToken(otherOwner.getId(), UserRole.BUSINESS_OWNER);

        UUID categoryId = UUID.randomUUID();
        jdbc.update("INSERT INTO category (id, name, kind) VALUES (?, ?, 'RESTAURANT')", categoryId, "Food IT " + suffix());
        cityId = UUID.randomUUID();
        jdbc.update("INSERT INTO city (id, name) VALUES (?, ?)", cityId, "City IT " + suffix());
        areaId = UUID.randomUUID();
        jdbc.update("INSERT INTO area (id, city_id, name) VALUES (?, ?, ?)", areaId, cityId, "Area IT " + suffix());
        businessId = insertBusiness(owner.getId(), categoryId, "Promo Kitchen " + suffix());
        insertBusiness(otherOwner.getId(), categoryId, "Rival Kitchen " + suffix());
        offerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO offer (id, business_id, title, offer_type, discount_value, valid_from, valid_until, status)
                VALUES (?, ?, 'Zinger 20% off', 'PERCENTAGE_DISCOUNT', 20, now() - interval '1 day', now() + interval '10 days', 'ACTIVE')
                """, offerId, businessId);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static String suffix() {
        String n = String.valueOf(System.nanoTime());
        return n.substring(n.length() - 8);
    }

    private User saveUser(UserRole role, String communityUsername, String staffRole) {
        return userRepository.save(User.builder()
                .phoneNumber("+8801" + suffix().substring(0, 8) + (int) (Math.random() * 10))
                .role(role)
                .name(role + " tester")
                .otpVerified(true)
                .passwordHash("$2a$10$abcdefghijklmnopqrstuuJ4t6x0bE2Qm0vXgk3H5yqk6n2k1V0bW")
                .communityUsername(communityUsername == null ? null : communityUsername.substring(0, Math.min(20, communityUsername.length())))
                .communityGender(communityUsername == null ? null : "F")
                .staffRole(staffRole)
                .build());
    }

    private UUID insertBusiness(UUID ownerId, UUID categoryId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO business (id, owner_user_id, name, slug, category_id, city_id, area_id, contact_number, location, verified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(90.3687, 23.8069), 4326), false)
                """, id, ownerId, name, "it-" + id, categoryId, cityId, areaId, "+88017" + suffix());
        return id;
    }

    private MvcResult call(HttpMethod method, String url, String token, Object body) throws Exception {
        return call(method, url, token, body, null);
    }

    private MvcResult call(HttpMethod method, String url, String token, Object body, String session) throws Exception {
        MockHttpServletRequestBuilder b = request(method, url).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            b.content(objectMapper.writeValueAsString(body));
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (session != null) {
            b.header("X-Promo-Session", session);
        }
        return mvc.perform(b).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private Map<String, Object> postBody(String type, String body, boolean publish) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("body", body);
        m.put("publish", publish);
        if ("OFFER".equals(type)) {
            m.put("offerId", offerId);
        }
        return m;
    }

    private UUID createBusinessPost(String type, String body) throws Exception {
        MvcResult r = call(HttpMethod.POST, "/api/v1/promo/businesses/" + businessId + "/posts", ownerToken, postBody(type, body, true));
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return UUID.fromString(json(r).get("id").asText());
    }

    private void patchPromotionSettings(Map<String, Object> patch) throws Exception {
        JsonNode current = json(call(HttpMethod.GET, "/api/v1/admin/promotions/settings", adminToken, null));
        JsonNode merged = objectMapper.readerForUpdating(current).readValue(objectMapper.writeValueAsString(patch));
        MvcResult r = call(HttpMethod.PUT, "/api/v1/admin/promotions/settings", adminToken, Map.of("promotions", merged, "reason", "test"));
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    }

    /** A live, paid, approved boost on a post, inserted directly (the payment flow has its own test). */
    private UUID insertActiveBoost(UUID postId) {
        UUID id = UUID.randomUUID();
        UUID packageId = jdbc.queryForObject("SELECT id FROM boost_package ORDER BY sort_order LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO boost (id, business_id, post_id, package_id, package_name, est_impressions, target_area_ids,
                                   start_at, end_at, status, price_bdt, payment_method, payment_ref, payment_verified_at,
                                   paid_amount, created_by)
                VALUES (?, ?, ?, ?, 'IT', 100000, ARRAY[?]::uuid[], now() - interval '1 hour', now() + interval '7 days',
                        'ACTIVE', 300, 'BKASH', ?, now(), 300, ?)
                """, id, businessId, postId, packageId, areaId, "IT" + suffix() + (int) (Math.random() * 1000), owner.getId());
        return id;
    }

    /** Organic member posts, inserted directly — the API would (rightly) rate-limit a new account. */
    private void createMemberPosts(int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update("""
                    INSERT INTO community_post (id, author_user_id, body, post_type, topic, status, created_at, updated_at, version)
                    VALUES (?, ?, ?, 'DISCUSSION', 'GENERAL', 'ACTIVE', now() + (? * interval '1 second'), now(), 0)
                    """, UUID.randomUUID(), member.getId(), "Organic post number " + i + " " + suffix(), i + 1);
        }
    }

    // -----------------------------------------------------------------
    // Posting as a business
    // -----------------------------------------------------------------

    @Test
    void cannotPostAsABusinessYouDoNotOwn() throws Exception {
        MvcResult r = call(HttpMethod.POST, "/api/v1/promo/businesses/" + businessId + "/posts", otherOwnerToken,
                postBody("GENERAL", "Trying to post as someone else's business here", true));
        assertThat(status(r)).isEqualTo(403);
        MvcResult asMember = call(HttpMethod.POST, "/api/v1/promo/businesses/" + businessId + "/posts", memberToken,
                postBody("GENERAL", "Trying to post as someone else's business here", true));
        assertThat(status(asMember)).isEqualTo(403);
    }

    @Test
    void businessPostShowsTheBusinessNotTheOwner() throws Exception {
        UUID postId = createBusinessPost("GENERAL", "Fresh kacchi every Friday at our Mirpur branch!");
        JsonNode post = json(call(HttpMethod.GET, "/api/v1/community/posts/" + postId, null, null));
        assertThat(post.get("business").get("id").asText()).isEqualTo(businessId.toString());
        assertThat(post.get("author").get("communityUsername").isNull()).isTrue();
        assertThat(post.get("promotion").get("cta").asText()).isEqualTo("VIEW_BUSINESS");
    }

    @Test
    void weeklyPostLimitIsEnforced() throws Exception {
        patchPromotionSettings(Map.of("businessPostsPerWeek", 2));
        createBusinessPost("GENERAL", "First business post of this week for the test");
        createBusinessPost("ANNOUNCEMENT", "Second business post of this week for the test");
        MvcResult third = call(HttpMethod.POST, "/api/v1/promo/businesses/" + businessId + "/posts", ownerToken,
                postBody("GENERAL", "Third business post should be over the weekly limit", true));
        assertThat(status(third)).isEqualTo(400);
        assertThat(third.getResponse().getContentAsString()).contains("limit");
        // A draft doesn't count and is still allowed.
        MvcResult draft = call(HttpMethod.POST, "/api/v1/promo/businesses/" + businessId + "/posts", ownerToken,
                postBody("GENERAL", "A draft is always allowed even over the limit", false));
        assertThat(status(draft)).isEqualTo(200);
        assertThat(json(draft).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void bannedCategoriesAndForeignLinksAreRejected() throws Exception {
        MvcResult alcohol = call(HttpMethod.POST, "/api/v1/promo/businesses/" + businessId + "/posts", ownerToken,
                postBody("GENERAL", "Happy hour: cold beer and wine for everyone tonight", true));
        assertThat(status(alcohol)).isEqualTo(400);
        MvcResult link = call(HttpMethod.POST, "/api/v1/promo/businesses/" + businessId + "/posts", ownerToken,
                postBody("GENERAL", "Order from our partner at https://some-other-shop.com today", true));
        assertThat(status(link)).isEqualTo(400);
    }

    @Test
    void approvalRequiredPutsBusinessPostsInTheQueue() throws Exception {
        patchPromotionSettings(Map.of("requireApprovalForBusinessPosts", true));
        UUID postId = createBusinessPost("GENERAL", "This post should wait for a moderator first");
        JsonNode mine = json(call(HttpMethod.GET, "/api/v1/community/posts/" + postId, ownerToken, null));
        assertThat(mine.get("status").asText()).isEqualTo("PENDING");
        assertThat(status(call(HttpMethod.GET, "/api/v1/community/posts/" + postId, memberToken, null))).isEqualTo(404);

        JsonNode queue = json(call(HttpMethod.GET, "/api/v1/admin/promotions/posts/pending", moderatorToken, null));
        assertThat(queue.findValuesAsText("postId")).contains(postId.toString());
        assertThat(status(call(HttpMethod.POST, "/api/v1/admin/promotions/posts/" + postId + "/approve", moderatorToken, Map.of()))).isEqualTo(204);
        assertThat(status(call(HttpMethod.GET, "/api/v1/community/posts/" + postId, memberToken, null))).isEqualTo(200);
    }

    @Test
    void businessMayCommentAsItselfOnlyOnItsOwnPosts() throws Exception {
        UUID ownPost = createBusinessPost("GENERAL", "Tell us your favourite dish from our menu!");
        MvcResult ok = call(HttpMethod.POST, "/api/v1/community/posts/" + ownPost + "/comments", ownerToken,
                Map.of("content", "Thanks everyone!", "asBusinessId", businessId));
        assertThat(status(ok)).as(ok.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(ok).get("business").get("id").asText()).isEqualTo(businessId.toString());

        MvcResult memberPost = call(HttpMethod.POST, "/api/v1/community/posts", memberToken,
                Map.of("body", "Which restaurant has the best kacchi?", "postType", "DISCUSSION", "topic", "GENERAL", "imageUrls", List.of()));
        UUID memberPostId = UUID.fromString(json(memberPost).get("id").asText());
        MvcResult notOwn = call(HttpMethod.POST, "/api/v1/community/posts/" + memberPostId + "/comments", ownerToken,
                Map.of("content", "Come to us!", "asBusinessId", businessId));
        assertThat(status(notOwn)).isEqualTo(403);
    }

    // -----------------------------------------------------------------
    // Boost + sponsored serving
    // -----------------------------------------------------------------

    @Test
    void boostCannotGoActiveWithoutVerifiedPayment() throws Exception {
        UUID postId = createBusinessPost("GENERAL", "Boost me — fresh biryani deals in the area this week");
        UUID packageId = jdbc.queryForObject("SELECT id FROM boost_package ORDER BY sort_order LIMIT 1", UUID.class);
        MvcResult created = call(HttpMethod.POST, "/api/v1/promo/posts/" + postId + "/boosts", ownerToken,
                Map.of("packageId", packageId, "radiusKm", 2));
        assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(200);
        UUID boostId = UUID.fromString(json(created).get("id").asText());
        assertThat(json(created).get("status").asText()).isEqualTo("PENDING_PAYMENT");

        // Approving before any verified payment is refused.
        assertThat(status(call(HttpMethod.POST, "/api/v1/admin/promotions/boosts/" + boostId + "/approve", moderatorToken, Map.of()))).isEqualTo(400);
        // A moderator can't verify payments (ADMIN only), and there's no payment yet anyway.
        assertThat(status(call(HttpMethod.POST, "/api/v1/admin/promotions/boosts/" + boostId + "/verify-payment", moderatorToken, Map.of()))).isEqualTo(403);
        assertThat(status(call(HttpMethod.POST, "/api/v1/admin/promotions/boosts/" + boostId + "/verify-payment", adminToken, Map.of()))).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT status FROM boost WHERE id = ?", String.class, boostId)).isEqualTo("PENDING_PAYMENT");

        patchPromotionSettings(Map.of("bkashNumber", "01700000000"));
        MvcResult paid = call(HttpMethod.POST, "/api/v1/promo/boosts/" + boostId + "/payment", ownerToken,
                Map.of("method", "BKASH", "transactionId", "TRX" + suffix()));
        assertThat(status(paid)).as(paid.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(status(call(HttpMethod.POST, "/api/v1/admin/promotions/boosts/" + boostId + "/verify-payment", adminToken, Map.of()))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM boost WHERE id = ?", String.class, boostId)).isEqualTo("PENDING_REVIEW");
        assertThat(status(call(HttpMethod.POST, "/api/v1/admin/promotions/boosts/" + boostId + "/approve", moderatorToken, Map.of()))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM boost WHERE id = ?", String.class, boostId)).isEqualTo("ACTIVE");
    }

    @Test
    void offerEndingSoonCannotBeBoostedAndSaysSo() throws Exception {
        UUID postId = createBusinessPost("OFFER", "Grab our Zinger deal — 20% off for a limited time!");
        JsonNode fresh = json(call(HttpMethod.GET, "/api/v1/community/posts/" + postId, ownerToken, null));
        assertThat(fresh.get("promotion").get("canBoost").asBoolean()).isTrue();

        jdbc.update("UPDATE offer SET valid_until = now() + interval '3 hours' WHERE id = ?", offerId);
        JsonNode endingSoon = json(call(HttpMethod.GET, "/api/v1/community/posts/" + postId, ownerToken, null));
        assertThat(endingSoon.get("promotion").get("canBoost").asBoolean()).isFalse();
        UUID packageId = jdbc.queryForObject("SELECT id FROM boost_package ORDER BY sort_order LIMIT 1", UUID.class);
        MvcResult r = call(HttpMethod.POST, "/api/v1/promo/posts/" + postId + "/boosts", ownerToken, Map.of("packageId", packageId, "radiusKm", 2));
        assertThat(status(r)).isEqualTo(400);
        assertThat(json(r).get("message").asText()).contains("less than 12 hours");
    }

    @Test
    void feedNeverHasMoreThanOneSponsoredPerNOrganic() throws Exception {
        patchPromotionSettings(Map.of("sponsoredFeedRatio", 5));
        createMemberPosts(20);
        UUID postId = createBusinessPost("GENERAL", "Sponsored slot test post for the feed ratio check");
        insertActiveBoost(postId);

        JsonNode feed = json(call(HttpMethod.GET, "/api/v1/community/posts?size=20", memberToken, null, "sess" + suffix() + "abcdef"));
        List<Boolean> sponsored = new ArrayList<>();
        feed.get("content").forEach(p -> sponsored.add(p.hasNonNull("sponsored")));
        assertThat(sponsored).contains(true);
        int organicSinceLast = 0;
        for (boolean s : sponsored) {
            if (s) {
                assertThat(organicSinceLast).as("organic posts before a sponsored slot").isGreaterThanOrEqualTo(5);
                organicSinceLast = 0;
            } else {
                organicSinceLast++;
            }
        }
        long organic = sponsored.stream().filter(s -> !s).count();
        long paid = sponsored.stream().filter(s -> s).count();
        assertThat(paid).isLessThanOrEqualTo(organic / 5);
    }

    @Test
    void sponsoredNeverAppearsInQuestionsCommentsOrReviews() throws Exception {
        createMemberPosts(10);
        UUID postId = createBusinessPost("GENERAL", "Sponsored placement must not leak into Q&A views");
        insertActiveBoost(postId);
        String session = "qa" + suffix() + "abcdef";
        JsonNode questions = json(call(HttpMethod.GET, "/api/v1/community/posts?postType=QUESTION&size=30", memberToken, null, session));
        questions.get("content").forEach(p -> assertThat(p.hasNonNull("sponsored")).isFalse());
        JsonNode comments = json(call(HttpMethod.GET, "/api/v1/community/posts/" + postId + "/comments", memberToken, null, session));
        assertThat(comments.toString()).doesNotContain("\"sponsored\"");
        MvcResult reviews = call(HttpMethod.GET, "/api/v1/reviews/business/" + businessId, null, null, session);
        assertThat(reviews.getResponse().getContentAsString()).doesNotContain("\"sponsored\"");
    }

    @Test
    void expiredOfferExpiresThePostAndLeavesSponsoredSlots() throws Exception {
        patchPromotionSettings(Map.of("sponsoredFeedRatio", 3));
        UUID postId = createBusinessPost("OFFER", "Grab our Zinger deal — 20% off for a limited time!");
        // Newer organic posts push the business post off page 1 — a post is never shown as
        // sponsored on the same page as its own organic copy.
        createMemberPosts(25);
        UUID boostId = insertActiveBoost(postId);
        String before = call(HttpMethod.GET, "/api/v1/community/posts?size=20", memberToken, null, "ex1" + suffix() + "abcdef")
                .getResponse().getContentAsString();
        assertThat(before).contains(boostId.toString());

        jdbc.update("UPDATE offer SET valid_until = now() - interval '1 minute' WHERE id = ?", offerId);
        // Served state flips immediately (read-time check), before the job even runs…
        String after = call(HttpMethod.GET, "/api/v1/community/posts?size=20", memberToken, null, "ex2" + suffix() + "abcdef")
                .getResponse().getContentAsString();
        assertThat(after).doesNotContain(boostId.toString());
        // …and the job records it.
        expiryJob.run();
        assertThat(jdbc.queryForObject("SELECT status FROM business_post WHERE post_id = ?", String.class, postId)).isEqualTo("EXPIRED");
        JsonNode post = json(call(HttpMethod.GET, "/api/v1/community/posts/" + postId, memberToken, null));
        assertThat(post.get("promotion").get("expired").asBoolean()).isTrue();
        assertThat(post.get("promotion").get("status").asText()).isEqualTo("EXPIRED");
    }

    @Test
    void promotionNeverChangesOrganicSearchRatingsOrVerification() throws Exception {
        UUID postId = createBusinessPost("GENERAL", "Search trust test: our kitchen is open late tonight");
        Map<String, Object> before = jdbc.queryForMap("SELECT average_rating, review_count, verified FROM business WHERE id = ?", businessId);
        String url = "/api/v1/businesses/smart-search?q=restaurant&size=30";
        JsonNode without = json(call(HttpMethod.GET, url, null, null, "s1" + suffix() + "abcdef"));
        insertActiveBoost(postId);
        JsonNode with = json(call(HttpMethod.GET, url, null, null, "s2" + suffix() + "abcdef"));

        assertThat(with.get("results")).isEqualTo(without.get("results"));
        assertThat(without.get("sponsored").isNull()).isTrue();
        Set<String> organicIds = new HashSet<>();
        with.get("results").get("content").forEach(b -> organicIds.add(b.get("id").asText()));
        if (organicIds.contains(businessId.toString())) {
            // Already visible organically → no duplicate ad for it on the same page.
            assertThat(with.get("sponsored").isNull()).isTrue();
        } else {
            assertThat(with.get("sponsored").get("business").get("id").asText()).isEqualTo(businessId.toString());
        }
        assertThat(jdbc.queryForMap("SELECT average_rating, review_count, verified FROM business WHERE id = ?", businessId)).isEqualTo(before);
    }

    @Test
    void adminPromotionPagesRenderAndModeratorsSeeOnlyTheQueues() throws Exception {
        UUID postId = createBusinessPost("GENERAL", "Admin page render test: open late tonight in the area");
        insertActiveBoost(postId);
        var asAdmin = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user(admin.getId().toString()).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
        var asModerator = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user(moderator.getId().toString()).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MODERATOR"));
        java.nio.file.Path out = java.nio.file.Path.of("target", "admin-render");
        java.nio.file.Files.createDirectories(out);
        for (String page : List.of("", "/boosts", "/settings", "/templates", "/packages", "/restrictions", "/revenue")) {
            MvcResult r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/promotions" + page).with(asAdmin)).andReturn();
            assertThat(r.getResponse().getStatus()).as("admin GET /admin/promotions" + page).isEqualTo(200);
            String html = r.getResponse().getContentAsString();
            assertThat(html).contains("Promotions");
            java.nio.file.Files.writeString(out.resolve((page.isEmpty() ? "queues" : page.substring(1)) + ".html"), html);

            int modStatus = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/promotions" + page).with(asModerator))
                    .andReturn().getResponse().getStatus();
            boolean staffPage = page.isEmpty() || page.equals("/boosts");
            assertThat(modStatus).as("moderator GET /admin/promotions" + page).isEqualTo(staffPage ? 200 : 403);
        }
    }

    @Test
    void sponsoredSearchResultNeverDuplicatesAnOrganicResultOnThePage() throws Exception {
        UUID postId = createBusinessPost("GENERAL", "Search duplicate test: our kitchen is open late tonight");
        UUID boostId = insertActiveBoost(postId);
        // Viewer in the boost's target area; fresh sessions so the frequency cap never interferes.
        Set<String> kinds = Set.of("RESTAURANT");
        assertThat(sponsoredService.forSearch(new SponsoredService.Viewer(areaId, null, null, "d1" + suffix() + "abcdef"),
                kinds, null, Set.of()).map(SponsoredService.SponsoredBusiness::boostId)).contains(boostId);
        assertThat(sponsoredService.forSearch(new SponsoredService.Viewer(areaId, null, null, "d2" + suffix() + "abcdef"),
                kinds, null, Set.of(businessId)).map(SponsoredService.SponsoredBusiness::boostId)).isNotEqualTo(Optional.of(boostId));
    }

    @Test
    void frequencyCapLimitsTheSameBoostPerViewerPerDay() throws Exception {
        // Frequency caps are counted in Redis only (shared by every API instance; no per-instance
        // fallback), so this needs a reachable Redis.
        org.junit.jupiter.api.Assumptions.assumeTrue(redisAvailable(), "Redis is not reachable");
        patchPromotionSettings(Map.of("sponsoredFeedRatio", 3, "frequencyCapPerDay", 2));
        createMemberPosts(9);
        UUID postId = createBusinessPost("GENERAL", "Frequency cap test post for sponsored slots");
        UUID boostId = insertActiveBoost(postId);
        String session = "fc" + suffix() + "abcdef";
        int seen = 0;
        for (int i = 0; i < 4; i++) {
            if (call(HttpMethod.GET, "/api/v1/community/posts?size=9", memberToken, null, session)
                    .getResponse().getContentAsString().contains(boostId.toString())) {
                seen++;
            }
        }
        assertThat(seen).isEqualTo(2);
    }

    // -----------------------------------------------------------------
    // Admin roles + ML fallback
    // -----------------------------------------------------------------

    @Test
    void adminOnlyEndpointsRejectEveryoneElse() throws Exception {
        UUID any = UUID.randomUUID();
        List<Object[]> adminOnly = List.of(
                new Object[]{HttpMethod.GET, "/api/v1/admin/promotions/settings"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/promotions/packages"},
                new Object[]{HttpMethod.POST, "/api/v1/admin/promotions/boosts/" + any + "/verify-payment"},
                new Object[]{HttpMethod.POST, "/api/v1/admin/promotions/boosts/" + any + "/refund"},
                new Object[]{HttpMethod.GET, "/api/v1/admin/promotions/revenue"});
        for (Object[] e : adminOnly) {
            assertThat(status(call((HttpMethod) e[0], (String) e[1], moderatorToken, Map.of("reason", "x")))).as("moderator " + e[1]).isEqualTo(403);
            assertThat(status(call((HttpMethod) e[0], (String) e[1], memberToken, Map.of("reason", "x")))).as("member " + e[1]).isEqualTo(403);
            assertThat(status(call((HttpMethod) e[0], (String) e[1], ownerToken, Map.of("reason", "x")))).as("owner " + e[1]).isEqualTo(403);
        }
        // Staff queues: moderators yes, members/owners no.
        assertThat(status(call(HttpMethod.GET, "/api/v1/admin/promotions/posts/pending", moderatorToken, null))).isEqualTo(200);
        assertThat(status(call(HttpMethod.GET, "/api/v1/admin/promotions/posts/pending", memberToken, null))).isEqualTo(403);
        assertThat(status(call(HttpMethod.GET, "/api/v1/admin/promotions/posts/pending", ownerToken, null))).isEqualTo(403);
    }

    @Test
    void captionsFallBackToTemplatesWhenTheMlServiceIsDown() throws Exception {
        MvcResult r = call(HttpMethod.POST, "/api/v1/promo/businesses/" + businessId + "/captions", ownerToken,
                Map.of("type", "OFFER", "offerId", offerId, "tone", "friendly"));
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.get("source").asText()).isEqualTo("TEMPLATE");
        assertThat(body.get("bn")).hasSize(3);
        assertThat(body.get("en")).hasSize(3);
        body.get("en").forEach(c -> assertThat(c.asText().length()).isLessThanOrEqualTo(220));
        assertThat(body.get("en").get(0).asText()).contains("Zinger 20% off");
    }

    // -----------------------------------------------------------------
    // V61: own uploads + "Your own design"
    // -----------------------------------------------------------------

    private String uploadUrl(String token, UUID business) throws Exception {
        MvcResult r = call(HttpMethod.POST, "/api/v1/promo/businesses/" + business + "/uploads", token, Map.of());
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode slot = json(r);
        assertThat(slot.get("objectKey").asText()).startsWith("promo/" + business + "/uploads/").endsWith(".jpg");
        return slot.get("url").asText();
    }

    private MvcResult saveCreative(String templateKey, String photoUrl, String fit) throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("headline", "Weekend special");
        data.put("photoUrl", photoUrl);
        data.put("imageFit", fit);
        data.put("showRating", false);
        data.put("showQr", false);
        data.put("showPrice", false);
        return call(HttpMethod.POST, "/api/v1/promo/businesses/" + businessId + "/creatives", ownerToken,
                Map.of("templateKey", templateKey, "data", data));
    }

    @Test
    void ownersCanUploadTheirOwnBannerAndUseIt() throws Exception {
        assertThat(status(call(HttpMethod.POST, "/api/v1/promo/businesses/" + businessId + "/uploads", otherOwnerToken, Map.of())))
                .isEqualTo(403);
        String url = uploadUrl(ownerToken, businessId);

        MvcResult custom = saveCreative("CUSTOM", url, "FILL");
        assertThat(status(custom)).as(custom.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(custom).get("creative").get("data").get("imageFit").asText()).isEqualTo("FILL");
        // The same upload also works as the photo inside a regular template.
        assertThat(status(saveCreative("SPLIT_PHOTO", url, null))).isEqualTo(200);

        JsonNode studio = json(call(HttpMethod.GET, "/api/v1/promo/businesses/" + businessId + "/studio", ownerToken, null));
        assertThat(studio.get("uploadsEnabled").asBoolean()).isTrue();
        assertThat(studio.get("uploads").toString()).contains(url);
        List<String> keys = new ArrayList<>();
        studio.get("templates").forEach(t -> keys.add(t.get("key").asText()));
        assertThat(keys).contains("CUSTOM", "SPLIT_PHOTO", "PRICE_SPOTLIGHT", "FESTIVE", "BIG_ANNOUNCEMENT");
    }

    @Test
    void uploadsAreCheckedAndCanBeTurnedOff() throws Exception {
        UUID rivalBusiness = jdbc.queryForObject("SELECT id FROM business WHERE owner_user_id = ?", UUID.class, otherOwner.getId());
        String rivalUpload = uploadUrl(otherOwnerToken, rivalBusiness);
        assertThat(status(saveCreative("CUSTOM", rivalUpload, null))).as("another business's upload").isEqualTo(400);
        assertThat(status(saveCreative("CUSTOM", null, null))).as("own design without an image").isEqualTo(400);
        assertThat(status(saveCreative("CUSTOM", uploadUrl(ownerToken, businessId), "STRETCH"))).as("bad fit").isEqualTo(400);

        patchPromotionSettings(Map.of("uploadsEnabled", false));
        assertThat(status(call(HttpMethod.POST, "/api/v1/promo/businesses/" + businessId + "/uploads", ownerToken, Map.of())))
                .isEqualTo(400);
    }

    @Test
    void uploadedImagePostsWaitForApprovalOnlyWhenTheSettingIsOn() throws Exception {
        patchPromotionSettings(Map.of("uploadedImagesRequireApproval", true));
        UUID uploaded = UUID.fromString(json(saveCreative("CUSTOM", uploadUrl(ownerToken, businessId), null))
                .get("creative").get("id").asText());
        UUID templated = UUID.fromString(json(saveCreative("BIG_ANNOUNCEMENT", null, null)).get("creative").get("id").asText());

        Map<String, Object> withUpload = postBody("GENERAL", "Our own banner for this weekend — come visit us!", true);
        withUpload.put("creativeId", uploaded);
        MvcResult held = call(HttpMethod.POST, "/api/v1/promo/businesses/" + businessId + "/posts", ownerToken, withUpload);
        assertThat(status(held)).as(held.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(held).get("promotion").get("status").asText()).isEqualTo("PENDING_REVIEW");

        Map<String, Object> withTemplate = postBody("GENERAL", "A template banner for this weekend — come visit us!", true);
        withTemplate.put("creativeId", templated);
        MvcResult live = call(HttpMethod.POST, "/api/v1/promo/businesses/" + businessId + "/posts", ownerToken, withTemplate);
        assertThat(status(live)).as(live.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(live).get("promotion").get("status").asText()).isEqualTo("PUBLISHED");
    }

    private boolean redisAvailable() {
        try {
            var redis = redisTemplates.getIfAvailable();
            return redis != null && "PONG".equalsIgnoreCase(
                    redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping()));
        } catch (RuntimeException e) {
            return false;
        }
    }
}

package com.bdreview.platform.admin;

import com.bdreview.platform.adminconfig.AdminConfigService;
import com.bdreview.platform.auth.JwtService;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.commerce.OrderService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
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
 * Admin panel Phase 2 (V65) end to end: commerce oversight, listing integrity (verification,
 * protected edits, duplicates/merge, data checks), review policy and homepage curation.
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
class AdminPhase2IntegrationTest {

    private static final String PASSWORD = "Secret-pass-123";

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PlatformSettingStore settingStore;
    @Autowired AdminConfigService adminConfig;
    @Autowired OrderService orderService;
    @Autowired com.bdreview.platform.listing.DuplicateService duplicateService;

    User admin;
    User moderator;
    User owner;
    User member;
    String ownerToken;
    String memberToken;
    UUID categoryId;
    UUID cityId;
    UUID areaId;
    UUID businessId;
    RequestPostProcessor asAdmin;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM platform_setting");
        settingStore.evict();
        jdbc.update("DELETE FROM admin_config");
        adminConfig.evict();

        admin = saveUser(UserRole.ADMIN, null, null);
        moderator = saveUser(UserRole.CONSUMER, "mod" + suffix(), User.STAFF_MODERATOR);
        owner = saveUser(UserRole.BUSINESS_OWNER, null, null);
        member = saveUser(UserRole.CONSUMER, "mem" + suffix(), null);
        ownerToken = jwtService.generateAccessToken(owner.getId(), UserRole.BUSINESS_OWNER);
        memberToken = jwtService.generateAccessToken(member.getId(), UserRole.CONSUMER);
        asAdmin = user(admin.getId().toString()).roles("ADMIN");

        categoryId = UUID.randomUUID();
        jdbc.update("INSERT INTO category (id, name, kind) VALUES (?, ?, 'RESTAURANT')", categoryId, "Food P2 " + suffix());
        cityId = UUID.randomUUID();
        jdbc.update("INSERT INTO city (id, name) VALUES (?, ?)", cityId, "City P2 " + suffix());
        areaId = UUID.randomUUID();
        jdbc.update("INSERT INTO area (id, city_id, name) VALUES (?, ?, ?)", areaId, cityId, "Area P2 " + suffix());
        businessId = insertBusiness(owner.getId(), "Phase Two Kitchen " + suffix(), 90.3687, 23.8069, "+88017" + suffix());
    }

    // =================================================================
    // 403 for roles without permission
    // =================================================================

    @Test
    void everyNewAdminEndpointIs403ForNonAdmins() throws Exception {
        UUID x = UUID.randomUUID();
        List<Object[]> endpoints = List.of(
                new Object[]{HttpMethod.GET, "/admin/commerce"},
                new Object[]{HttpMethod.POST, "/admin/commerce/settings"},
                new Object[]{HttpMethod.GET, "/admin/commerce/orders"},
                new Object[]{HttpMethod.GET, "/admin/commerce/orders/" + x},
                new Object[]{HttpMethod.POST, "/admin/commerce/orders/" + x + "/cancel"},
                new Object[]{HttpMethod.POST, "/admin/commerce/orders/" + x + "/resolve-dispute"},
                new Object[]{HttpMethod.GET, "/admin/commerce/bookings"},
                new Object[]{HttpMethod.GET, "/admin/commerce/bookings/" + x},
                new Object[]{HttpMethod.POST, "/admin/commerce/bookings/" + x + "/cancel"},
                new Object[]{HttpMethod.POST, "/admin/commerce/bookings/" + x + "/no-show"},
                new Object[]{HttpMethod.POST, "/admin/commerce/bookings/" + x + "/complete"},
                new Object[]{HttpMethod.GET, "/admin/commerce/offers"},
                new Object[]{HttpMethod.POST, "/admin/commerce/offers/" + x + "/end"},
                new Object[]{HttpMethod.POST, "/admin/commerce/offers/" + x + "/hide"},
                new Object[]{HttpMethod.GET, "/admin/verification"},
                new Object[]{HttpMethod.GET, "/admin/verification/" + x + "/document"},
                new Object[]{HttpMethod.POST, "/admin/verification/" + x + "/approve"},
                new Object[]{HttpMethod.POST, "/admin/verification/" + x + "/reject"},
                new Object[]{HttpMethod.POST, "/admin/verification/business/" + businessId + "/verify"},
                new Object[]{HttpMethod.POST, "/admin/verification/business/" + businessId + "/revoke"},
                new Object[]{HttpMethod.GET, "/admin/pending-changes"},
                new Object[]{HttpMethod.POST, "/admin/pending-changes/" + x + "/approve"},
                new Object[]{HttpMethod.POST, "/admin/pending-changes/" + x + "/reject"},
                new Object[]{HttpMethod.GET, "/admin/duplicates"},
                new Object[]{HttpMethod.POST, "/admin/duplicates/merge"},
                new Object[]{HttpMethod.POST, "/admin/duplicates/dismiss"},
                new Object[]{HttpMethod.GET, "/admin/data-checks"},
                new Object[]{HttpMethod.POST, "/admin/data-checks/delete"},
                new Object[]{HttpMethod.GET, "/admin/reviews/settings"},
                new Object[]{HttpMethod.POST, "/admin/reviews/settings"},
                new Object[]{HttpMethod.POST, "/admin/reviews/bulk"},
                new Object[]{HttpMethod.GET, "/admin/homepage"},
                new Object[]{HttpMethod.POST, "/admin/homepage"},
                new Object[]{HttpMethod.POST, "/admin/homepage/hero-image"});
        RequestPostProcessor asModerator = user(moderator.getId().toString()).roles("MODERATOR");
        RequestPostProcessor asConsumer = user(member.getId().toString()).roles("CONSUMER");
        for (Object[] e : endpoints) {
            for (RequestPostProcessor who : List.of(asModerator, asConsumer)) {
                int status = mvc.perform(request((HttpMethod) e[0], (String) e[1]).with(who).with(csrf())
                        .param("reason", "x").param("action", "hide").param("keepId", businessId.toString())
                        .param("removeId", x.toString()).param("a", businessId.toString()).param("b", x.toString())
                        .param("orderAutoCancelMinutes", "5").param("bookingNoShowGraceMinutes", "5")
                        .param("maxActiveOffersPerBusiness", "1").param("minLength", "1").param("editWindowHours", "1")
                        .param("maxReviewsPerUserPerDay", "1").param("notRecommendedThreshold", "1").param("hiddenThreshold", "2"))
                        .andReturn().getResponse().getStatus();
                assertThat(status).as(e[0] + " " + e[1]).isEqualTo(403);
            }
        }
        // Nothing changed.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_config", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT verified FROM business WHERE id = ?", Boolean.class, businessId)).isFalse();
    }

    @Test
    void adminPagesRender() throws Exception {
        UUID orderId = insertOrder(businessId, member.getId(), "PENDING", 45);
        UUID bookingId = insertBooking(businessId, member.getId());
        insertOffer(businessId, "Too good", "PERCENTAGE_DISCOUNT", 95, "ACTIVE", 2);
        for (String page : List.of("/admin/commerce", "/admin/commerce/orders", "/admin/commerce/orders?stuckMinutes=30&status=PENDING",
                "/admin/commerce/orders/" + orderId, "/admin/commerce/bookings", "/admin/commerce/bookings?status=NO_SHOW",
                "/admin/commerce/bookings/" + bookingId, "/admin/commerce/offers", "/admin/commerce/offers?flagged=true",
                "/admin/verification", "/admin/pending-changes", "/admin/duplicates", "/admin/data-checks",
                "/admin/data-checks?check=TEST_LIKE_NAME", "/admin/data-checks?check=MISSING_HOURS", "/admin/data-checks?check=INACTIVE",
                "/admin/data-checks?check=MISSING_COORDINATES", "/admin/reviews", "/admin/reviews?minScore=10&maxScore=90&business=Kitchen",
                "/admin/reviews/settings", "/admin/homepage", "/admin/businesses/" + businessId)) {
            MvcResult r = mvc.perform(get(page).with(asAdmin)).andReturn();
            assertThat(r.getResponse().getStatus()).as(page + " " + r.getResponse().getErrorMessage()).isEqualTo(200);
        }
        String offers = mvc.perform(get("/admin/commerce/offers?flagged=true&business=" + businessId).with(asAdmin))
                .andReturn().getResponse().getContentAsString();
        assertThat(offers).contains("Too good").contains("&gt;90% off");
    }

    // =================================================================
    // Commerce
    // =================================================================

    @Test
    void changingOrderAutoCancelMinutesChangesTheJob() throws Exception {
        UUID tenMinutesOld = insertOrder(businessId, member.getId(), "PENDING", 10);

        orderService.autoExpireStalePendingOrders(); // default 24 h → too young to cancel
        assertThat(orderStatus(tenMinutesOld)).isEqualTo("PENDING");

        mvc.perform(post("/admin/commerce/settings").with(asAdmin).with(csrf())
                .param("orderAutoCancelMinutes", "5").param("bookingNoShowGraceMinutes", "60")
                .param("maxActiveOffersPerBusiness", "10").param("reason", "faster SLA"));
        assertThat(adminConfig.commerce().getOrderAutoCancelMinutes()).isEqualTo(5);

        orderService.autoExpireStalePendingOrders();
        assertThat(orderStatus(tenMinutesOld)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT note FROM order_status_event WHERE order_id = ? AND to_status = 'CANCELLED'",
                String.class, tenMinutesOld)).contains("5 minutes");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'CONFIG_COMMERCE_SAVED' AND reason = 'faster SLA'",
                Long.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void adminCancelsOrderAndBookingAndHandlesOffers() throws Exception {
        UUID orderId = insertOrder(businessId, member.getId(), "ACCEPTED", 5);
        mvc.perform(post("/admin/commerce/orders/" + orderId + "/cancel").with(asAdmin).with(csrf()));
        assertThat(orderStatus(orderId)).as("no reason → no change").isEqualTo("ACCEPTED");
        mvc.perform(post("/admin/commerce/orders/" + orderId + "/cancel").with(asAdmin).with(csrf()).param("reason", "Customer called support"));
        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(audit("ORDER", orderId, "ORDER_CANCELLED_BY_ADMIN")).isEqualTo(1);
        mvc.perform(post("/admin/commerce/orders/" + orderId + "/resolve-dispute").with(asAdmin).with(csrf()).param("reason", "Refunded in cash"));
        assertThat(jdbc.queryForObject("SELECT dispute_note FROM business_order WHERE id = ?", String.class, orderId)).isEqualTo("Refunded in cash");

        UUID bookingId = insertBooking(businessId, member.getId());
        mvc.perform(post("/admin/commerce/bookings/" + bookingId + "/no-show").with(asAdmin).with(csrf()).param("reason", "Did not come"));
        assertThat(jdbc.queryForObject("SELECT status FROM business_booking WHERE id = ?", String.class, bookingId)).isEqualTo("NO_SHOW");

        UUID offerId = insertOffer(businessId, "Half price", "PERCENTAGE_DISCOUNT", 50, "ACTIVE", 2);
        mvc.perform(post("/admin/commerce/offers/" + offerId + "/hide").with(asAdmin).with(csrf()).param("reason", "Misleading"));
        assertThat(jdbc.queryForObject("SELECT status FROM offer WHERE id = ?", String.class, offerId)).isEqualTo("REJECTED");
        UUID liveOffer = insertOffer(businessId, "Tea deal", "FIXED_AMOUNT_DISCOUNT", 20, "ACTIVE", 2);
        mvc.perform(post("/admin/commerce/offers/" + liveOffer + "/end").with(asAdmin).with(csrf()).param("reason", "Ended early"));
        assertThat(jdbc.queryForObject("SELECT valid_until <= now() FROM offer WHERE id = ?", Boolean.class, liveOffer)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE recipient_user_id = ? AND type = 'ADMIN_NOTICE'",
                Long.class, owner.getId())).isGreaterThanOrEqualTo(0);
    }

    // =================================================================
    // Listing integrity
    // =================================================================

    @Test
    void pendingProtectedEditDoesNotChangeThePublicBusinessUntilApproved() throws Exception {
        String slug = jdbc.queryForObject("SELECT slug FROM business WHERE id = ?", String.class, businessId);
        String oldName = jdbc.queryForObject("SELECT name FROM business WHERE id = ?", String.class, businessId);
        mvc.perform(post("/admin/verification/business/" + businessId + "/verify").with(asAdmin).with(csrf()).param("reason", "Checked in person"));
        assertThat(jdbc.queryForObject("SELECT verified FROM business WHERE id = ?", Boolean.class, businessId)).isTrue();

        MvcResult r = call(HttpMethod.PUT, "/api/v1/businesses/" + businessId, ownerToken, updateBody("Renamed Kitchen", "Owner's new tagline"));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode pub = objectMapper.readTree(get200("/api/v1/businesses/" + slug, null));
        assertThat(pub.get("name").asText()).isEqualTo(oldName);
        assertThat(pub.get("description").asText()).as("unprotected fields still update").isEqualTo("Owner's new tagline");

        JsonNode pending = objectMapper.readTree(get200("/api/v1/businesses/" + businessId + "/pending-changes", ownerToken));
        assertThat(pending.get("changedFields").toString()).contains("name");
        UUID changeId = UUID.fromString(pending.get("id").asText());

        mvc.perform(post("/admin/pending-changes/" + changeId + "/approve").with(asAdmin).with(csrf()).param("reason", "Rebrand confirmed"));
        assertThat(objectMapper.readTree(get200("/api/v1/businesses/" + slug, null)).get("name").asText()).isEqualTo("Renamed Kitchen");
        assertThat(audit("BUSINESS", businessId, "PROTECTED_EDIT_APPROVED")).isEqualTo(1);
        assertThat(call(HttpMethod.GET, "/api/v1/businesses/" + businessId + "/pending-changes", ownerToken, null)
                .getResponse().getStatus()).isEqualTo(204);

        // A rejected change is discarded.
        call(HttpMethod.PUT, "/api/v1/businesses/" + businessId, ownerToken, updateBody("Wrong Name", "x"));
        UUID second = UUID.fromString(objectMapper.readTree(get200("/api/v1/businesses/" + businessId + "/pending-changes", ownerToken))
                .get("id").asText());
        mvc.perform(post("/admin/pending-changes/" + second + "/reject").with(asAdmin).with(csrf()).param("reason", "Not your legal name"));
        assertThat(objectMapper.readTree(get200("/api/v1/businesses/" + slug, null)).get("name").asText()).isEqualTo("Renamed Kitchen");
    }

    @Test
    void verificationRequestFlow() throws Exception {
        MvcResult req = call(HttpMethod.POST, "/api/v1/businesses/" + businessId + "/verification-requests", ownerToken,
                Map.of("method", "PHONE", "note", "Call me after 5pm"));
        assertThat(req.getResponse().getStatus()).as(req.getResponse().getContentAsString()).isEqualTo(200);
        UUID requestId = UUID.fromString(objectMapper.readTree(req.getResponse().getContentAsString()).get("id").asText());
        assertThat(call(HttpMethod.POST, "/api/v1/businesses/" + businessId + "/verification-requests", memberToken,
                Map.of("method", "PHONE")).getResponse().getStatus()).as("not the owner").isEqualTo(403);

        mvc.perform(post("/admin/verification/" + requestId + "/approve").with(asAdmin).with(csrf()).param("reason", "Called, confirmed"));
        assertThat(jdbc.queryForObject("SELECT verified FROM business WHERE id = ?", Boolean.class, businessId)).isTrue();
        String page = mvc.perform(get("/admin/businesses/" + businessId).with(asAdmin)).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Called, confirmed");
    }

    @Test
    void mergeMovesRelatedRowsAndTheOldSlugRedirects() throws Exception {
        UUID keep = businessId;
        UUID remove = insertBusiness(owner.getId(), "Phase Two Kitchen Dup " + suffix(), 90.3688, 23.8070, "+88018" + suffix());
        String removedSlug = jdbc.queryForObject("SELECT slug FROM business WHERE id = ?", String.class, remove);
        String keptSlug = jdbc.queryForObject("SELECT slug FROM business WHERE id = ?", String.class, keep);

        User reviewer2 = saveUser(UserRole.CONSUMER, null, null);
        insertReview(remove, member.getId(), 5);
        insertReview(remove, reviewer2.getId(), 3);
        insertReview(keep, member.getId(), 4); // same user reviewed both → that one stays on the archived listing
        jdbc.update("INSERT INTO business_photo (id, business_id, url, sort_order) VALUES (?, ?, ?, 0)", UUID.randomUUID(), remove, "http://x/p.jpg");
        UUID offer = insertOffer(remove, "Dup offer", "OTHER", null, "DRAFT", 3);
        jdbc.update("INSERT INTO business_menu_item (id, business_id, name) VALUES (?, ?, 'Kacchi')", UUID.randomUUID(), remove);
        jdbc.update("INSERT INTO bookmark (id, user_id, business_id) VALUES (?, ?, ?)", UUID.randomUUID(), reviewer2.getId(), remove);

        mvc.perform(post("/admin/duplicates/merge").with(asAdmin).with(csrf())
                .param("keepId", keep.toString()).param("removeId", remove.toString()).param("reason", "Same restaurant"));

        assertThat(count("SELECT count(*) FROM review WHERE business_id = ? AND deleted_at IS NULL", keep)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM business_photo WHERE business_id = ?", keep)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT business_id FROM offer WHERE id = ?", UUID.class, offer)).isEqualTo(keep);
        assertThat(count("SELECT count(*) FROM business_menu_item WHERE business_id = ?", keep)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bookmark WHERE business_id = ?", keep)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM business WHERE id = ?", Boolean.class, remove)).isTrue();
        assertThat(jdbc.queryForObject("SELECT review_count FROM business WHERE id = ?", Integer.class, keep)).isEqualTo(2);
        assertThat(audit("BUSINESS", keep, "BUSINESS_MERGED")).isEqualTo(1);

        MvcResult redirect = mvc.perform(get("/api/v1/businesses/" + removedSlug)).andReturn();
        assertThat(redirect.getResponse().getStatus()).isEqualTo(301);
        assertThat(redirect.getResponse().getHeader("Location")).isEqualTo("/api/v1/businesses/" + keptSlug);
    }

    @Test
    void duplicateFinderAndDismiss() throws Exception {
        UUID twin = insertBusiness(member.getId(), jdbc.queryForObject("SELECT name FROM business WHERE id = ?", String.class, businessId),
                90.3688, 23.8070, "+88019" + suffix());
        assertThat(hasPair(businessId, twin)).isTrue();
        assertThat(mvc.perform(get("/admin/duplicates").with(asAdmin)).andReturn().getResponse().getContentAsString())
                .contains(twin.toString());
        mvc.perform(post("/admin/duplicates/dismiss").with(asAdmin).with(csrf())
                .param("a", businessId.toString()).param("b", twin.toString()).param("reason", "Two branches, different owners"));
        // (the twin may still pair with other similar test listings nearby — only this pair is dismissed)
        assertThat(hasPair(businessId, twin)).isFalse();
    }

    private boolean hasPair(UUID x, UUID y) {
        return duplicateService.candidates(1000).stream().anyMatch(p ->
                (x.equals(p.get("a_id")) && y.equals(p.get("b_id"))) || (y.equals(p.get("a_id")) && x.equals(p.get("b_id"))));
    }

    @Test
    void dataChecksFindAndBulkDelete() throws Exception {
        UUID junk = insertBusiness(owner.getId(), "xx", 0, 0, "+88016" + suffix());
        String page = mvc.perform(get("/admin/data-checks?check=TEST_LIKE_NAME").with(asAdmin)).andReturn().getResponse().getContentAsString();
        assertThat(page).contains(junk.toString());
        assertThat(mvc.perform(get("/admin/data-checks?check=MISSING_COORDINATES").with(asAdmin)).andReturn().getResponse()
                .getContentAsString()).contains(junk.toString());
        mvc.perform(post("/admin/data-checks/delete").with(asAdmin).with(csrf()).param("ids", junk.toString()).param("reason", "Test entry"));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM business WHERE id = ?", Boolean.class, junk)).isTrue();
    }

    // =================================================================
    // Review policy
    // =================================================================

    @Test
    void reviewPolicyIsReadAtRuntime() throws Exception {
        mvc.perform(post("/admin/reviews/settings").with(asAdmin).with(csrf())
                .param("minLength", "30").param("editWindowHours", "1").param("maxReviewsPerUserPerDay", "1")
                .param("notRecommendedThreshold", "40").param("hiddenThreshold", "80").param("reason", "stricter"));
        MvcResult tooShort = call(HttpMethod.POST, "/api/v1/reviews", memberToken,
                Map.of("businessId", businessId, "rating", 4, "content", "Nice food, good service."));
        assertThat(tooShort.getResponse().getStatus()).isEqualTo(400);
        assertThat(tooShort.getResponse().getContentAsString()).contains("30 characters");

        MvcResult ok = call(HttpMethod.POST, "/api/v1/reviews", memberToken,
                Map.of("businessId", businessId, "rating", 4, "content", "Nice food, good service, fair prices and friendly staff."));
        assertThat(ok.getResponse().getStatus()).as(ok.getResponse().getContentAsString()).isEqualTo(200);
        UUID other = insertBusiness(owner.getId(), "Another Place " + suffix(), 90.37, 23.81, "+88015" + suffix());
        MvcResult limited = call(HttpMethod.POST, "/api/v1/reviews", memberToken,
                Map.of("businessId", other, "rating", 4, "content", "Second review today should hit the daily limit set by admin."));
        assertThat(limited.getResponse().getStatus()).isEqualTo(400);
        assertThat(limited.getResponse().getContentAsString()).contains("limit of 1");
    }

    @Test
    void bulkHideKeepsAggregatesRight() throws Exception {
        UUID r1 = insertReview(businessId, member.getId(), 5);
        jdbc.update("UPDATE business SET review_count = 1, rating_sum = 5, average_rating = 5 WHERE id = ?", businessId);
        mvc.perform(post("/admin/reviews/bulk").with(asAdmin).with(csrf()).param("ids", r1.toString())
                .param("action", "hide").param("reason", "Spam"));
        assertThat(jdbc.queryForObject("SELECT visibility_status FROM review WHERE id = ?", String.class, r1)).isEqualTo("HIDDEN");
        assertThat(jdbc.queryForObject("SELECT review_count FROM business WHERE id = ?", Integer.class, businessId)).isZero();
        assertThat(audit("REVIEW", r1, "REVIEW_HIDDEN_BULK")).isEqualTo(1);
    }

    // =================================================================
    // Homepage
    // =================================================================

    @Test
    void excludedBusinessesNeverAppearInTrendingAndPinsComeFirst() throws Exception {
        UUID pinned = insertBusiness(owner.getId(), "Pinned Place " + suffix(), 90.37, 23.81, "+88014" + suffix());
        UUID excluded = insertBusiness(owner.getId(), "Excluded Place " + suffix(), 90.371, 23.811, "+88013" + suffix());
        // Give the excluded one lots of activity so it would otherwise rank at the top.
        for (int i = 0; i < 30; i++) {
            jdbc.update("INSERT INTO business_reaction_event (id, business_id, user_id, reaction_type) VALUES (?, ?, ?, 'LOVE')",
                    UUID.randomUUID(), excluded, member.getId());
        }
        jdbc.update("UPDATE business SET total_love_count = 9999 WHERE id = ?", excluded);
        String until = LocalDate.now().plusDays(3).toString();
        mvc.perform(post("/admin/homepage").with(asAdmin).with(csrf())
                .param("heroTitle", "Eat well in Dhaka").param("heroSubtitle", "Curated by the Jachai team")
                .param("featured", categoryId.toString()).param("pos_" + categoryId, "1")
                .param("trending.minReviewCount", "0").param("trending.excluded", excluded.toString())
                .param("trending.pin0", pinned.toString()).param("trending.pinUntil0", until)
                .param("trending.pin1", excluded.toString()).param("trending.pinUntil1", until)
                .param("mostLoved.minReviewCount", "0").param("mostLoved.excluded", excluded.toString())
                .param("reason", "Launch curation"));

        for (String sort : List.of("trending", "most_loved")) {
            for (int page = 0; page < 3; page++) {
                String body = get200("/api/v1/businesses/search?sort=" + sort + "&size=50&page=" + page, null);
                assertThat(body).as(sort + " page " + page).doesNotContain(excluded.toString());
            }
        }
        JsonNode trending = objectMapper.readTree(get200("/api/v1/businesses/search?sort=trending&size=10", null));
        assertThat(trending.get("content").get(0).get("id").asText()).isEqualTo(pinned.toString());

        // Minimum review count hides listings below it (pins are exempt).
        mvc.perform(post("/admin/homepage").with(asAdmin).with(csrf())
                .param("trending.minReviewCount", "1000").param("mostLoved.minReviewCount", "0").param("reason", "only popular"));
        JsonNode strict = objectMapper.readTree(get200("/api/v1/businesses/search?sort=trending&size=10", null));
        assertThat(strict.get("content").size()).isZero();

        JsonNode home = objectMapper.readTree(get200("/api/v1/home", null));
        assertThat(home.get("featuredCategories").size()).isZero(); // second save cleared the featured list
    }

    @Test
    void homepageTextsCategoriesAndModeratedHeroImage() throws Exception {
        mvc.perform(post("/admin/homepage").with(asAdmin).with(csrf())
                .param("heroTitle", "Eat well in Dhaka").param("featured", categoryId.toString()).param("pos_" + categoryId, "1")
                .param("reason", "Launch"));
        JsonNode home = objectMapper.readTree(get200("/api/v1/home", null));
        assertThat(home.get("heroTitle").asText()).isEqualTo("Eat well in Dhaka");
        assertThat(home.get("featuredCategories").get(0).get("id").asText()).isEqualTo(categoryId.toString());

        mvc.perform(multipart("/admin/homepage/hero-image")
                .file(new MockMultipartFile("image", "hero.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2}))
                .param("reason", "New season").with(asAdmin).with(csrf()));
        assertThat(objectMapper.readTree(get200("/api/v1/home", null)).get("heroImageUrl").isNull())
                .as("waits for photo moderation").isTrue();
        UUID entry = jdbc.queryForObject("SELECT id FROM photo_moderation WHERE source_type = 'HERO' AND status = 'PENDING' "
                + "ORDER BY created_at DESC LIMIT 1", UUID.class);
        mvc.perform(post("/admin/photos/" + entry + "/approve").with(asAdmin).with(csrf()).param("reason", "ok"));
        assertThat(objectMapper.readTree(get200("/api/v1/home", null)).get("heroImageUrl").asText()).contains("homepage/hero/");
    }

    // =================================================================
    // helpers
    // =================================================================

    private UUID insertBusiness(UUID ownerId, String name, double lng, double lat, String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO business (id, owner_user_id, name, slug, category_id, city_id, area_id, contact_number, location, verified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), false)
                """, id, ownerId, name, "p2-" + id, categoryId, cityId, areaId, phone, lng, lat);
        return id;
    }

    private UUID insertOrder(UUID business, UUID customer, String status, int minutesAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO business_order (id, business_id, customer_user_id, order_number, status, fulfillment_type, subtotal,
                                            total_amount, payment_method, customer_name_snapshot, customer_phone_snapshot, created_at)
                VALUES (?, ?, ?, ?, ?, 'PICKUP', 500, 500, 'PAY_AT_BUSINESS', 'Test Customer', '+8801700000000',
                        now() - make_interval(mins => ?))
                """, id, business, customer, "T" + suffix() + (int) (Math.random() * 1000), status, minutesAgo);
        return id;
    }

    private UUID insertBooking(UUID business, UUID customer) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO business_booking (id, business_id, customer_user_id, booking_number, status, service_name_snapshot,
                                              preferred_date, preferred_time, duration_minutes_snapshot, buffer_minutes_snapshot,
                                              slot_start, slot_end, customer_name_snapshot, customer_phone_snapshot)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 'Haircut', current_date, '10:00', 30, 0,
                        current_date + time '10:00', current_date + time '10:30', 'Test Customer', '+8801700000000')
                """, id, business, customer, "B" + suffix() + (int) (Math.random() * 1000));
        return id;
    }

    private UUID insertOffer(UUID business, String title, String type, Integer discount, String status, int daysLeft) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO offer (id, business_id, title, offer_type, discount_value, valid_from, valid_until, status)
                VALUES (?, ?, ?, ?, ?, now() - interval '1 day', now() + make_interval(days => ?), ?)
                """, id, business, title, type, discount, daysLeft, status);
        return id;
    }

    private UUID insertReview(UUID business, UUID userId, int rating) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO review (id, business_id, user_id, rating, content) VALUES (?, ?, ?, ?, 'A perfectly fine review text.')",
                id, business, userId, rating);
        return id;
    }

    private Map<String, Object> updateBody(String name, String description) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("categoryId", categoryId);
        m.put("cityId", cityId);
        m.put("areaId", areaId);
        m.put("contactNumber", jdbc.queryForObject("SELECT contact_number FROM business WHERE id = ?", String.class, businessId));
        m.put("description", description);
        m.put("latitude", 23.8069);
        m.put("longitude", 90.3687);
        m.put("priceTier", "MODERATE");
        m.put("attributeIds", new ArrayList<>());
        return m;
    }

    private String orderStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM business_order WHERE id = ?", String.class, id);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long audit(String entityType, UUID entityId, String action) {
        return count("SELECT count(*) FROM audit_log WHERE entity_type = ? AND entity_id = ? AND action = ?", entityType, entityId, action);
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
                .name(role + " P2 tester")
                .otpVerified(true)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .communityUsername(communityUsername == null ? null : communityUsername.substring(0, Math.min(20, communityUsername.length())))
                .communityGender(communityUsername == null ? null : "M")
                .staffRole(staffRole)
                .build());
    }
}

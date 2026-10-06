package com.bdreview.platform.community.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every admin-tunable Community knob, stored as one JSONB document in {@code community_settings}
 * (V56) and cached by {@link CommunitySettingsService}. Field initialisers ARE the defaults: an
 * empty document (or a key added in a later release) falls back to them, and unknown keys are
 * ignored so an older build can still read a newer document.
 *
 * <p>Enforced server-side by community.moderation.CommunityPolicyService on every write — the
 * public subset ({@link PublicCommunitySettings}) only exists so the Next.js app can mirror the
 * rules for UX.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommunitySettings {

    private General general = new General();
    private PostTypes postTypes = new PostTypes();
    private Areas areas = new Areas();
    private ContentRules content = new ContentRules();
    private RateLimits rateLimits = new RateLimits();
    private NewUsers newUsers = new NewUsers();
    private AutoModeration autoModeration = new AutoModeration();
    private Features features = new Features();
    /** V58 business promotion knobs — edited on the admin "Promotions → Settings" page. */
    private Promotions promotions = new Promotions();

    /**
     * Business promotion (V58). Enforced server-side by promo.BusinessPostService,
     * promo.BoostService and promo.SponsoredService. None of these ever affect reviews, ratings,
     * organic search order, the Verified badge or trust signals.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Promotions {
        private boolean businessPostsEnabled = true;
        private boolean boostsEnabled = true;
        private int businessPostsPerWeek = 3;
        private boolean requireApprovalForBusinessPosts = false;
        private int minBodyLength = 20;
        /** At most one sponsored item after every N organic feed posts; 0 = no sponsored feed items. */
        private int sponsoredFeedRatio = 8;
        private boolean sponsoredInSearch = true;
        private boolean featuredNearbyEnabled = true;
        /** The same boost at most this many times per viewer (session) per day. */
        private int frequencyCapPerDay = 2;
        /** Content categories that may not be promoted — see promo.PromotionContentRules for the keyword lists. */
        private List<String> bannedCategories = new ArrayList<>(List.of("MEDICAL_CLAIMS", "ALCOHOL", "TOBACCO", "WEAPONS", "POLITICAL"));
        /** Extra words/phrases (Bangla or English) that block a promotion, on top of the category lists. */
        private List<String> extraBannedKeywords = new ArrayList<>();
        /** Merchant numbers shown to owners on the manual Boost payment step. */
        private String bkashNumber = "";
        private String nagadNumber = "";
        private boolean captionAiEnabled = true;
        private int captionsPerBusinessPerDay = 20;
        /** After payment is verified, a moderator must still approve the boost before it goes live. */
        private boolean boostRequiresReview = true;
        /** V61: owners may upload their own banner/photos in the Design Studio. */
        private boolean uploadsEnabled = true;
        /** V61: a business post whose creative uses an uploaded image waits for moderator approval. */
        private boolean uploadedImagesRequireApproval = false;
    }

    /** Markdown shown in the community sidebar and composer. */
    private String rulesMarkdown = """
            1. Be respectful — no harassment, hate speech or personal attacks.
            2. Keep it real — share honest, first-hand experiences.
            3. No spam, self-promotion or misleading links.
            4. Protect privacy — never post someone else's personal information.
            5. Stay on topic and pick the right topic for your post.""";

    /** Quick-pick removal/resolution reasons in the admin panel (free text is always allowed too). */
    private List<String> reasonTemplates = new ArrayList<>(List.of(
            "Spam or self-promotion",
            "Harassment or personal attack",
            "Hate speech",
            "Misleading or false information",
            "Personal information / privacy violation",
            "Off-topic",
            "Inappropriate or explicit content"));

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class General {
        private boolean communityEnabled = true;
        private String maintenanceMessage = "Community is under maintenance right now. Please check back soon.";
        private boolean readOnly = false;
        private String readOnlyMessage = "Community is read-only right now — you can browse, but posting, commenting and voting are paused.";
        private boolean guestsCanRead = true;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PostTypes {
        private boolean discussionEnabled = true;
        private boolean questionEnabled = true;
        /** RECOMMENDATION — labelled "Review" in the UI. */
        private boolean recommendationEnabled = true;
        private boolean pollEnabled = true;
        private boolean imagesEnabled = true;
        private int maxImagesPerPost = 10;
        private int maxImageSizeMb = 5;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Areas {
        /** Areas a post may be tagged with; empty = every area. */
        private List<UUID> allowedAreaIds = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentRules {
        private int postBodyMin = 1;
        private int postBodyMax = 5000;
        /** Applied to a QUESTION's title, or its body when it has no separate title. */
        private int questionTitleMin = 0;
        private int questionTitleMax = 150;
        private int commentMin = 1;
        private int commentMax = 2000;
        private int maxLinksPerPost = 5;
        /** 0 = off. */
        private int blockLinksForAccountsNewerThanDays = 0;
        /** Whole-word, case-insensitive, Bangla + English. */
        private List<String> bannedWords = new ArrayList<>();
        /** BLOCK = reject with 400; FLAG = accept but hold in the pending queue. */
        private String bannedWordsMode = "FLAG";
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RateLimits {
        /**
         * No trust-level system exists, so the tier is derived: an account is TRUSTED once it is
         * at least this many days old, or when a moderator used "Approve and trust user".
         */
        private int trustedAfterDays = 7;
        private Tier newUsers = new Tier(5, 20, 30, 10);
        private Tier trustedUsers = new Tier(30, 60, 60, 30);
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tier {
        private int postsPerDay;
        private int commentsPerHour;
        private int votesPerMinute;
        private int reportsPerDay;

        public Tier(int postsPerDay, int commentsPerHour, int votesPerMinute, int reportsPerDay) {
            this.postsPerDay = postsPerDay;
            this.commentsPerHour = commentsPerHour;
            this.votesPerMinute = votesPerMinute;
            this.reportsPerDay = reportsPerDay;
        }
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NewUsers {
        private boolean approvalRequired = false;
        /** A user's first N posts are held for approval. */
        private int approvalPostCount = 3;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AutoModeration {
        /** Hide a post/comment once this many DIFFERENT users have open reports on it; 0 = off. */
        private int autoHideReportThreshold = 5;
        /** Hold any post containing a link from a not-yet-trusted account. */
        private boolean autoFlagLinkPostsFromNewAccounts = false;
        /** Dashboard "Needs attention": posts with at least this many downvotes and a negative score. */
        private int heavilyDownvotedThreshold = 5;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Features {
        /**
         * Legacy (V56) — no longer read. Since V63 every feature flag, NID included, lives in
         * platform_setting (features.FeatureFlagService); kept only so old stored documents still parse.
         */
        private Boolean nidVerificationEnabled = null;
    }
}

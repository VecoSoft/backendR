package com.bdreview.platform.community.moderation;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.RateLimitExceededException;
import com.bdreview.platform.common.RedisRateLimiter;
import com.bdreview.platform.community.CommunityContentStatus;
import com.bdreview.platform.community.CommunityPostCommentRepository;
import com.bdreview.platform.community.CommunityPostRepository;
import com.bdreview.platform.community.CommunityPostType;
import com.bdreview.platform.community.settings.CommunityConfig;
import com.bdreview.platform.community.settings.CommunitySettings;
import com.bdreview.platform.community.settings.CommunitySettingsService;
import com.bdreview.platform.community.settings.TopicView;
import com.bdreview.platform.report.ReportRepository;
import com.bdreview.platform.report.ReportTargetType;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side enforcement of every community setting and restriction. Called by
 * CommunityPostService / CommunityUsernameService / ReportService before any community write,
 * and by the read paths for maintenance / guest-read rules. Nothing here trusts the client —
 * the Next.js app only mirrors these rules (via GET /api/v1/community/settings) for UX.
 */
@Service
public class CommunityPolicyService {

    public enum Action {
        POST("post"), COMMENT("comment"), VOTE("vote"), REPORT("report"), FOLLOW("follow"),
        POLL_VOTE("vote in polls"), PROFILE("change your community profile"),
        /** Editing/managing your own existing content — restriction-checked, never rate-limited. */
        EDIT("edit your posts");

        final String verb;

        Action(String verb) {
            this.verb = verb;
        }
    }

    /** Why new content is held for review instead of going live. */
    public static final String HOLD_NEW_USER = "NEW_USER";
    public static final String HOLD_BANNED_WORD = "BANNED_WORD";
    public static final String HOLD_LINKS = "LINKS";
    public static final String HOLD_REPORTS = "REPORTS";

    private static final Pattern LINK = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+|\\b[a-z0-9-]+\\.(?:com|net|org|io|bd|info|xyz|me|co)(?:/\\S*)?\\b");
    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").withZone(ZoneId.of("Asia/Dhaka"));

    private final CommunitySettingsService settingsService;
    private final CommunityRestrictionRepository restrictionRepository;
    private final CommunityPostRepository postRepository;
    private final CommunityPostCommentRepository commentRepository;
    private final ReportRepository reportRepository;

    /**
     * One-minute vote counter per user, in Redis so it holds across API instances (votes can be
     * toggled, so row counts would under-count). Setter-injected so unit tests can construct the
     * service without it; without a limiter, votes are not limited.
     */
    private RedisRateLimiter voteLimiter;
    private volatile BannedWordMatcher bannedWordMatcher = new BannedWordMatcher(List.of());

    public CommunityPolicyService(CommunitySettingsService settingsService,
                                  CommunityRestrictionRepository restrictionRepository,
                                  CommunityPostRepository postRepository,
                                  CommunityPostCommentRepository commentRepository,
                                  ReportRepository reportRepository) {
        this.settingsService = settingsService;
        this.restrictionRepository = restrictionRepository;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.reportRepository = reportRepository;
    }

    public CommunityConfig config() {
        return settingsService.config();
    }

    // -----------------------------------------------------------------
    // Availability
    // -----------------------------------------------------------------

    /** Staff (ADMIN/MODERATOR) keep reading during maintenance so they can check things. */
    public void assertCanRead(UUID viewerUserId) {
        CommunitySettings.General g = config().settings().getGeneral();
        if (!g.isCommunityEnabled() && !isStaff()) {
            throw new CommunityUnavailableException(g.getMaintenanceMessage());
        }
        if (!g.isGuestsCanRead() && viewerUserId == null) {
            throw new ForbiddenException("Log in to read the community.");
        }
    }

    /**
     * Gate for every community write: maintenance, read-only mode, then an active
     * MUTE/SUSPEND/BAN (→ 403 with the reason and end date), then the per-tier rate limit.
     */
    public void assertCanWrite(User user, Action action) {
        CommunitySettings.General g = config().settings().getGeneral();
        if (!g.isCommunityEnabled()) {
            throw new CommunityUnavailableException(g.getMaintenanceMessage());
        }
        if (g.isReadOnly()) {
            throw new ForbiddenException(g.getReadOnlyMessage());
        }
        activeBlockingRestriction(user.getId()).ifPresent(r -> {
            throw new ForbiddenException(restrictionMessage(r, action));
        });
        enforceRateLimit(user, action);
    }

    /** Restriction check only (no maintenance/read-only/rate rules) — community username and avatar changes. */
    public void assertNotRestricted(UUID userId, Action action) {
        activeBlockingRestriction(userId).ifPresent(r -> {
            throw new ForbiddenException(restrictionMessage(r, action));
        });
    }

    public Optional<CommunityRestriction> activeBlockingRestriction(UUID userId) {
        return restrictionRepository.findInEffect(userId, Instant.now()).stream()
                .filter(CommunityRestriction::blocksWrites)
                .max(Comparator.comparing((CommunityRestriction r) -> r.getEndsAt() == null ? Instant.MAX : r.getEndsAt()));
    }

    public static String restrictionMessage(CommunityRestriction r, Action action) {
        String what = switch (r.getType()) {
            case MUTE -> "You've been muted in the community";
            case SUSPEND -> "Your community access is suspended";
            case BAN -> "You've been banned from the community";
            case WARN -> "You've received a warning";
        };
        String until = r.getEndsAt() == null ? "" : " until " + UNTIL.format(r.getEndsAt());
        String verb = action == null ? "" : ", so you can't " + action.verb + " right now";
        return what + until + verb + ". Reason: " + r.getReason();
    }

    private static boolean isStaff() {
        return CurrentUser.hasRole("ADMIN") || CurrentUser.hasRole("MODERATOR");
    }

    // -----------------------------------------------------------------
    // Trust tier + rate limits
    // -----------------------------------------------------------------

    public boolean isTrusted(User user) {
        if (user.isCommunityTrusted()) {
            return true;
        }
        int days = config().settings().getRateLimits().getTrustedAfterDays();
        return user.getCreatedAt() != null && user.getCreatedAt().isBefore(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    public CommunitySettings.Tier tier(User user) {
        CommunitySettings.RateLimits r = config().settings().getRateLimits();
        return isTrusted(user) ? r.getTrustedUsers() : r.getNewUsers();
    }

    private void enforceRateLimit(User user, Action action) {
        CommunitySettings.Tier tier = tier(user);
        Instant now = Instant.now();
        switch (action) {
            case POST -> limit(postRepository.countByAuthorUserIdAndCreatedAtAfter(user.getId(), now.minus(1, ChronoUnit.DAYS)),
                    tier.getPostsPerDay(), "You've reached today's posting limit (" + tier.getPostsPerDay() + " posts per day). Please try again later.");
            case COMMENT -> limit(commentRepository.countByAuthorUserIdAndCreatedAtAfter(user.getId(), now.minus(1, ChronoUnit.HOURS)),
                    tier.getCommentsPerHour(), "You're commenting too frequently (" + tier.getCommentsPerHour() + " per hour). Please try again later.");
            case REPORT -> limit(reportRepository.countByReporterUserIdAndTargetTypeInAndCreatedAtAfter(user.getId(),
                            List.of(ReportTargetType.COMMUNITY_POST, ReportTargetType.COMMUNITY_COMMENT, ReportTargetType.COMMUNITY_PROFILE),
                            now.minus(1, ChronoUnit.DAYS)),
                    tier.getReportsPerDay(), "You've reached today's reporting limit. Please try again tomorrow.");
            case VOTE, POLL_VOTE -> {
                if (!tryVote(user.getId(), tier.getVotesPerMinute())) {
                    throw new RateLimitExceededException("You're voting too quickly — please slow down.");
                }
            }
            default -> {
            }
        }
    }

    private static void limit(long recent, int max, String message) {
        if (recent >= max) {
            throw new RateLimitExceededException(message);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setVoteLimiter(RedisRateLimiter voteLimiter) {
        this.voteLimiter = voteLimiter;
    }

    private boolean tryVote(UUID userId, int perMinute) {
        return voteLimiter == null || voteLimiter.tryAcquire("community-vote:" + userId, perMinute, Duration.ofMinutes(1));
    }

    // -----------------------------------------------------------------
    // Content rules
    // -----------------------------------------------------------------

    /**
     * Validates a new/edited post against every content setting. Throws 400 for a hard
     * violation; returns the hold reason when the post must wait for approval (null = publish).
     */
    public String checkPost(User author, String title, String body, CommunityPostType type, String topicCode,
                            int imageCount, UUID areaId, boolean isNew) {
        CommunityConfig cfg = config();
        CommunitySettings s = cfg.settings();
        CommunitySettings.ContentRules c = s.getContent();

        if (type != null && !cfg.postTypeEnabled(type)) {
            throw new BadRequestException(typeLabel(type) + " posts are turned off right now.");
        }
        TopicView topic = cfg.topic(topicCode).orElseThrow(() -> new BadRequestException("Unknown topic"));
        if (!topic.enabled()) {
            throw new BadRequestException("The \"" + topic.label() + "\" topic isn't available for new posts.");
        }
        if (imageCount > 0 && !s.getPostTypes().isImagesEnabled()) {
            throw new BadRequestException("Images are turned off for community posts right now.");
        }
        if (imageCount > s.getPostTypes().getMaxImagesPerPost()) {
            throw new BadRequestException("A post can have at most " + s.getPostTypes().getMaxImagesPerPost() + " photos");
        }
        if (areaId != null && !s.getAreas().getAllowedAreaIds().isEmpty() && !s.getAreas().getAllowedAreaIds().contains(areaId)) {
            throw new BadRequestException("That area isn't available in the community.");
        }

        int bodyLen = length(body);
        if (bodyLen < c.getPostBodyMin()) {
            throw new BadRequestException("Your post is too short — write at least " + c.getPostBodyMin() + " characters.");
        }
        if (bodyLen > c.getPostBodyMax()) {
            throw new BadRequestException("Your post is too long — keep it under " + c.getPostBodyMax() + " characters.");
        }
        if (type == CommunityPostType.QUESTION) {
            String headline = title != null && !title.isBlank() ? title : body;
            int len = length(headline);
            if (len < c.getQuestionTitleMin()) {
                throw new BadRequestException("Your question is too short — at least " + c.getQuestionTitleMin() + " characters.");
            }
            if (title != null && !title.isBlank() && len > c.getQuestionTitleMax()) {
                throw new BadRequestException("Your question title is too long — keep it under " + c.getQuestionTitleMax() + " characters.");
            }
        }

        String text = (title == null ? "" : title + "\n") + (body == null ? "" : body);
        int links = countLinks(text);
        if (links > c.getMaxLinksPerPost()) {
            throw new BadRequestException("Too many links — a post can include at most " + c.getMaxLinksPerPost() + ".");
        }
        if (links > 0 && c.getBlockLinksForAccountsNewerThanDays() > 0 && author.getCreatedAt() != null
                && author.getCreatedAt().isAfter(Instant.now().minus(c.getBlockLinksForAccountsNewerThanDays(), ChronoUnit.DAYS))) {
            throw new BadRequestException("New accounts can't post links yet — please try again in a few days.");
        }

        String hold = bannedWordsHold(text);
        if (hold == null && links > 0 && s.getAutoModeration().isAutoFlagLinkPostsFromNewAccounts() && !isTrusted(author)) {
            hold = HOLD_LINKS;
        }
        if (hold == null && isNew && s.getNewUsers().isApprovalRequired() && !author.isCommunityTrusted()
                && postRepository.countByAuthorUserIdAndStatus(author.getId(), CommunityContentStatus.ACTIVE) < s.getNewUsers().getApprovalPostCount()) {
            hold = HOLD_NEW_USER;
        }
        return hold;
    }

    /** Same contract as {@link #checkPost} for a comment. */
    public String checkComment(String content) {
        CommunitySettings.ContentRules c = config().settings().getContent();
        int len = length(content);
        if (len < c.getCommentMin()) {
            throw new BadRequestException("Your comment is too short — write at least " + c.getCommentMin() + " characters.");
        }
        if (len > c.getCommentMax()) {
            throw new BadRequestException("Your comment is too long — keep it under " + c.getCommentMax() + " characters.");
        }
        return bannedWordsHold(content);
    }

    private String bannedWordsHold(String text) {
        CommunitySettings.ContentRules c = config().settings().getContent();
        if (c.getBannedWords().isEmpty() || text == null) {
            return null;
        }
        BannedWordMatcher matcher = bannedWordMatcher;
        if (!matcher.words.equals(c.getBannedWords())) {
            matcher = new BannedWordMatcher(c.getBannedWords());
            bannedWordMatcher = matcher;
        }
        if (!matcher.matches(text)) {
            return null;
        }
        if ("BLOCK".equals(c.getBannedWordsMode())) {
            throw new BadRequestException("Your post contains words that aren't allowed in the community. Please edit it and try again.");
        }
        return HOLD_BANNED_WORD;
    }

    public List<String> findBannedWords(String text) {
        List<String> words = config().settings().getContent().getBannedWords();
        return new BannedWordMatcher(words).found(text);
    }

    static int countLinks(String text) {
        if (text == null) {
            return 0;
        }
        Matcher m = LINK.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static int length(String s) {
        return s == null ? 0 : s.trim().codePointCount(0, s.trim().length());
    }

    private static String typeLabel(CommunityPostType type) {
        return switch (type) {
            case DISCUSSION -> "Discussion";
            case QUESTION -> "Question";
            case RECOMMENDATION -> "Review";
            case POLL -> "Poll";
        };
    }

    /**
     * Whole-word, case-insensitive matcher that works for Bangla as well as English: a "word
     * boundary" is any position not flanked by a letter, combining mark (Bangla vowel signs are
     * \p{M}) or digit — Java's \b doesn't treat Bangla marks as word characters.
     */
    static final class BannedWordMatcher {
        final List<String> words;
        private final Pattern pattern;

        BannedWordMatcher(List<String> words) {
            this.words = List.copyOf(words);
            if (words.isEmpty()) {
                this.pattern = null;
                return;
            }
            StringJoiner alt = new StringJoiner("|");
            words.stream().filter(w -> !w.isBlank()).sorted(Comparator.comparingInt(String::length).reversed())
                    .forEach(w -> alt.add(Pattern.quote(w.trim())));
            this.pattern = Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}])(?:" + alt + ")(?![\\p{L}\\p{M}\\p{N}])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }

        boolean matches(String text) {
            return pattern != null && text != null && pattern.matcher(text).find();
        }

        List<String> found(String text) {
            if (pattern == null || text == null) {
                return List.of();
            }
            Set<String> out = new LinkedHashSet<>();
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                out.add(m.group());
            }
            return List.copyOf(out);
        }
    }
}

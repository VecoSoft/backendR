package com.bdreview.platform.community.moderation;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.community.*;
import com.bdreview.platform.moderation.AuditLog;
import com.bdreview.platform.moderation.AuditLogRepository;
import com.bdreview.platform.report.Report;
import com.bdreview.platform.report.ReportRepository;
import com.bdreview.platform.report.ReportStatus;
import com.bdreview.platform.report.ReportTargetType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Read side of the admin Community section: dashboard numbers, filterable post/comment/member
 * tables, the grouped report queue, the pending queue and the audit log. Staff-only — every
 * public method checks the caller's role (the controllers also carry @PreAuthorize).
 */
@Service
public class CommunityAdminQueryService {

    public static final int PAGE_SIZE = 25;
    private static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");
    private static final List<ReportTargetType> COMMUNITY_TARGETS =
            List.of(ReportTargetType.COMMUNITY_POST, ReportTargetType.COMMUNITY_COMMENT, ReportTargetType.COMMUNITY_PROFILE);

    private final JdbcTemplate jdbc;
    private final CommunityPostRepository postRepository;
    private final CommunityPostCommentRepository commentRepository;
    private final CommunityPostPhotoRepository photoRepository;
    private final UserRepository userRepository;
    private final ReportRepository reportRepository;
    private final CommunityRestrictionRepository restrictionRepository;
    private final AuditLogRepository auditLogRepository;
    private final CommunityPolicyService policy;

    public CommunityAdminQueryService(JdbcTemplate jdbc,
                                      CommunityPostRepository postRepository,
                                      CommunityPostCommentRepository commentRepository,
                                      CommunityPostPhotoRepository photoRepository,
                                      UserRepository userRepository,
                                      ReportRepository reportRepository,
                                      CommunityRestrictionRepository restrictionRepository,
                                      AuditLogRepository auditLogRepository,
                                      CommunityPolicyService policy) {
        this.jdbc = jdbc;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.photoRepository = photoRepository;
        this.userRepository = userRepository;
        this.reportRepository = reportRepository;
        this.restrictionRepository = restrictionRepository;
        this.auditLogRepository = auditLogRepository;
        this.policy = policy;
    }

    // -----------------------------------------------------------------
    // Dashboard
    // -----------------------------------------------------------------

    public record DashboardStats(long postsToday, long posts7d, long commentsToday, long openReports, long pendingApprovals,
                                 long removedToday, long newCommunityUsers7d, long activeRestrictions) {
    }

    public record DailyPoint(String day, long posts, long comments, long reports) {
    }

    public record Dashboard(DashboardStats stats, List<DailyPoint> last30Days, List<ReportGroup> newestReports,
                            List<CommunityPost> autoFlagged, List<CommunityPost> heavilyDownvoted, Map<UUID, User> authors) {
    }

    public Dashboard dashboard() {
        CommunityModerationService.requireStaff();
        Instant startOfToday = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        DashboardStats stats = new DashboardStats(
                count("SELECT COUNT(*) FROM community_post WHERE deleted_at IS NULL AND created_at >= ?", startOfToday),
                count("SELECT COUNT(*) FROM community_post WHERE deleted_at IS NULL AND created_at >= ?", sevenDaysAgo),
                count("SELECT COUNT(*) FROM community_post_comment WHERE deleted_at IS NULL AND created_at >= ?", startOfToday),
                reportRepository.countByTargetTypeInAndStatus(COMMUNITY_TARGETS, ReportStatus.PENDING),
                count("SELECT (SELECT COUNT(*) FROM community_post WHERE deleted_at IS NULL AND status IN ('PENDING','HIDDEN'))"
                        + " + (SELECT COUNT(*) FROM community_post_comment WHERE deleted_at IS NULL AND status IN ('PENDING','HIDDEN'))"),
                count("SELECT (SELECT COUNT(*) FROM community_post WHERE removed_at >= ?) + (SELECT COUNT(*) FROM community_post_comment WHERE removed_at >= ?)",
                        startOfToday, startOfToday),
                count("SELECT COUNT(*) FROM app_user WHERE community_username IS NOT NULL AND created_at >= ?", sevenDaysAgo),
                count("SELECT COUNT(*) FROM community_restriction WHERE status = 'ACTIVE' AND type <> 'WARN'"
                        + " AND starts_at <= now() AND (ends_at IS NULL OR ends_at > now())"));

        List<DailyPoint> series = jdbc.query("""
                SELECT to_char(d.day, 'YYYY-MM-DD') AS day,
                       (SELECT COUNT(*) FROM community_post p WHERE p.deleted_at IS NULL
                            AND (p.created_at AT TIME ZONE 'Asia/Dhaka')::date = d.day) AS posts,
                       (SELECT COUNT(*) FROM community_post_comment c WHERE c.deleted_at IS NULL
                            AND (c.created_at AT TIME ZONE 'Asia/Dhaka')::date = d.day) AS comments,
                       (SELECT COUNT(*) FROM report r WHERE r.target_type IN ('COMMUNITY_POST','COMMUNITY_COMMENT','COMMUNITY_PROFILE')
                            AND (r.created_at AT TIME ZONE 'Asia/Dhaka')::date = d.day) AS reports
                FROM generate_series((now() AT TIME ZONE 'Asia/Dhaka')::date - 29, (now() AT TIME ZONE 'Asia/Dhaka')::date, interval '1 day') AS d(day)
                ORDER BY d.day
                """, (rs, i) -> new DailyPoint(rs.getString("day"), rs.getLong("posts"), rs.getLong("comments"), rs.getLong("reports")));

        List<ReportGroup> newest = reportGroups(null, PageRequest.of(0, 5)).getContent();
        List<CommunityPost> flagged = postRepository.findAll(
                (root, q, cb) -> cb.and(cb.isNull(root.get("deletedAt")),
                        root.get("status").in(CommunityContentStatus.PENDING, CommunityContentStatus.HIDDEN)),
                PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        int threshold = policy.config().settings().getAutoModeration().getHeavilyDownvotedThreshold();
        List<CommunityPost> downvoted = postRepository.findAll(
                (root, q, cb) -> cb.and(cb.isNull(root.get("deletedAt")),
                        cb.equal(root.get("status"), CommunityContentStatus.ACTIVE),
                        cb.greaterThanOrEqualTo(root.get("downvoteCount"), threshold),
                        cb.lessThan(root.get("upvoteCount"), root.get("downvoteCount"))),
                PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "downvoteCount"))).getContent();
        Set<UUID> authorIds = new HashSet<>();
        flagged.forEach(p -> authorIds.add(p.getAuthorUserId()));
        downvoted.forEach(p -> authorIds.add(p.getAuthorUserId()));
        return new Dashboard(stats, series, newest, flagged, downvoted, users(authorIds));
    }

    private long count(String sql, Object... args) {
        Object[] converted = Arrays.stream(args).map(a -> a instanceof Instant i ? Timestamp.from(i) : a).toArray();
        Long n = jdbc.queryForObject(sql, Long.class, converted);
        return n == null ? 0 : n;
    }

    // -----------------------------------------------------------------
    // Posts table
    // -----------------------------------------------------------------

    public record PostFilter(String q, String type, String topic, UUID areaId, String status, LocalDate from, LocalDate to,
                             Boolean reported, Boolean hasImages, String sort) {
    }

    public Page<CommunityPost> posts(PostFilter f, int page) {
        CommunityModerationService.requireStaff();
        Sort sort = switch (f.sort() == null ? "newest" : f.sort()) {
            case "reported" -> Sort.by(Sort.Direction.DESC, "reportCount").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "voted" -> Sort.by(Sort.Direction.DESC, "upvoteCount").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "oldest" -> Sort.by(Sort.Direction.ASC, "createdAt");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
        Specification<CommunityPost> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isNull(root.get("deletedAt")));
            if (f.q() != null && !f.q().isBlank()) {
                String q = f.q().trim();
                List<Predicate> any = new ArrayList<>();
                String like = "%" + q.toLowerCase(Locale.ROOT) + "%";
                any.add(cb.like(cb.lower(cb.coalesce(root.get("title"), "")), like));
                any.add(cb.like(cb.lower(cb.coalesce(root.get("body"), "")), like));
                Subquery<UUID> byUser = query.subquery(UUID.class);
                var u = byUser.from(User.class);
                byUser.select(u.get("id")).where(cb.like(cb.lower(u.get("communityUsername")), like.replace("u/", "")));
                any.add(root.get("authorUserId").in(byUser));
                parseUuid(q).ifPresent(id -> any.add(cb.equal(root.get("id"), id)));
                ps.add(cb.or(any.toArray(Predicate[]::new)));
            }
            if (f.type() != null && !f.type().isBlank()) {
                ps.add(cb.equal(root.get("postType"), CommunityPostType.valueOf(f.type())));
            }
            if (f.topic() != null && !f.topic().isBlank()) {
                ps.add(cb.equal(root.get("topic"), f.topic()));
            }
            if (f.areaId() != null) {
                ps.add(cb.equal(root.get("areaId"), f.areaId()));
            }
            if (f.status() != null && !f.status().isBlank()) {
                switch (f.status()) {
                    case "LOCKED" -> ps.add(cb.isTrue(root.get("locked")));
                    case "PINNED" -> ps.add(cb.isTrue(root.get("pinned")));
                    case "FEATURED" -> ps.add(cb.isTrue(root.get("featured")));
                    case "OFFICIAL" -> ps.add(cb.isTrue(root.get("official")));
                    default -> ps.add(cb.equal(root.get("status"), CommunityContentStatus.valueOf(f.status())));
                }
            }
            if (f.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from().atStartOfDay(ZONE).toInstant()));
            }
            if (f.to() != null) {
                ps.add(cb.lessThan(root.get("createdAt"), f.to().plusDays(1).atStartOfDay(ZONE).toInstant()));
            }
            if (Boolean.TRUE.equals(f.reported())) {
                ps.add(cb.greaterThan(root.get("reportCount"), 0));
            }
            if (Boolean.TRUE.equals(f.hasImages())) {
                Subquery<UUID> photos = query.subquery(UUID.class);
                var ph = photos.from(CommunityPostPhoto.class);
                photos.select(ph.get("postId"));
                ps.add(cb.or(root.get("id").in(photos), cb.isNotNull(root.get("imageUrl"))));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return postRepository.findAll(spec, PageRequest.of(Math.max(page, 0), PAGE_SIZE, sort));
    }

    // -----------------------------------------------------------------
    // Comments table
    // -----------------------------------------------------------------

    public record CommentFilter(String q, UUID postId, String username, String status, Boolean reported) {
    }

    public Page<CommunityPostComment> comments(CommentFilter f, int page) {
        CommunityModerationService.requireStaff();
        Specification<CommunityPostComment> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isNull(root.get("deletedAt")));
            if (f.q() != null && !f.q().isBlank()) {
                String q = f.q().trim();
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("content")), "%" + q.toLowerCase(Locale.ROOT) + "%"));
                parseUuid(q).ifPresent(id -> any.add(cb.equal(root.get("id"), id)));
                ps.add(cb.or(any.toArray(Predicate[]::new)));
            }
            if (f.postId() != null) {
                ps.add(cb.equal(root.get("postId"), f.postId()));
            }
            if (f.username() != null && !f.username().isBlank()) {
                Subquery<UUID> byUser = query.subquery(UUID.class);
                var u = byUser.from(User.class);
                byUser.select(u.get("id")).where(cb.equal(cb.lower(u.get("communityUsername")),
                        f.username().trim().replaceFirst("^u/", "").toLowerCase(Locale.ROOT)));
                ps.add(root.get("authorUserId").in(byUser));
            }
            if (f.status() != null && !f.status().isBlank()) {
                ps.add(cb.equal(root.get("status"), CommunityContentStatus.valueOf(f.status())));
            }
            if (Boolean.TRUE.equals(f.reported())) {
                ps.add(cb.greaterThan(root.get("reportCount"), 0));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return commentRepository.findAll(spec, PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    // -----------------------------------------------------------------
    // Reports queue — grouped by target
    // -----------------------------------------------------------------

    public record ReportGroup(String targetType, UUID targetId, long openCount, List<String> reasons, Instant firstReportedAt,
                              Instant lastReportedAt, String preview, String authorUsername, UUID authorUserId,
                              String contentStatus, UUID postId) {
    }

    /** Open community reports grouped by target, most-reported first; {@code type} narrows to one target type. */
    public Page<ReportGroup> reportGroups(String type, Pageable pageable) {
        CommunityModerationService.requireStaff();
        String typeFilter = type == null || type.isBlank() ? null : type;
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (SELECT 1 FROM report
                  WHERE status = 'PENDING' AND target_type IN ('COMMUNITY_POST','COMMUNITY_COMMENT','COMMUNITY_PROFILE')
                    AND (CAST(? AS varchar) IS NULL OR target_type = CAST(? AS varchar))
                  GROUP BY target_type, target_id) g
                """, Long.class, typeFilter, typeFilter);
        List<Object[]> rows = jdbc.query("""
                SELECT target_type, target_id, COUNT(*) AS n, string_agg(DISTINCT reason, ',') AS reasons,
                       MIN(created_at) AS first_at, MAX(created_at) AS last_at
                FROM report
                WHERE status = 'PENDING' AND target_type IN ('COMMUNITY_POST','COMMUNITY_COMMENT','COMMUNITY_PROFILE')
                  AND (CAST(? AS varchar) IS NULL OR target_type = CAST(? AS varchar))
                GROUP BY target_type, target_id
                ORDER BY COUNT(*) DESC, MAX(created_at) DESC
                LIMIT ? OFFSET ?
                """, (rs, i) -> new Object[]{rs.getString(1), rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant()},
                typeFilter, typeFilter, pageable.getPageSize(), pageable.getOffset());
        List<ReportGroup> groups = new ArrayList<>();
        for (Object[] r : rows) {
            groups.add(describe((String) r[0], (UUID) r[1], (Long) r[2],
                    List.of(((String) r[3]).split(",")), (Instant) r[4], (Instant) r[5]));
        }
        return new PageImpl<>(groups, pageable, total == null ? 0 : total);
    }

    private ReportGroup describe(String type, UUID targetId, long n, List<String> reasons, Instant first, Instant last) {
        String preview = "(not found)";
        UUID author = null;
        String status = null;
        UUID postId = null;
        switch (ReportTargetType.valueOf(type)) {
            case COMMUNITY_POST -> {
                Optional<CommunityPost> p = postRepository.findById(targetId);
                if (p.isPresent()) {
                    preview = truncate(p.get().getTitle() != null ? p.get().getTitle() + " — " + nz(p.get().getBody()) : nz(p.get().getBody()));
                    author = p.get().getAuthorUserId();
                    status = p.get().getDeletedAt() != null ? "DELETED" : p.get().getStatus().name();
                    postId = targetId;
                }
            }
            case COMMUNITY_COMMENT -> {
                Optional<CommunityPostComment> c = commentRepository.findById(targetId);
                if (c.isPresent()) {
                    preview = truncate(c.get().getContent());
                    author = c.get().getAuthorUserId();
                    status = c.get().getDeletedAt() != null ? "DELETED" : c.get().getStatus().name();
                    postId = c.get().getPostId();
                }
            }
            case COMMUNITY_PROFILE -> {
                Optional<User> u = userRepository.findByCommunityProfileId(targetId);
                if (u.isPresent()) {
                    preview = "Profile u/" + u.get().getCommunityUsername();
                    author = u.get().getId();
                    status = "PROFILE";
                }
            }
            default -> {
            }
        }
        String username = author == null ? null : userRepository.findById(author).map(User::getCommunityUsername).orElse(null);
        return new ReportGroup(type, targetId, n, reasons, first, last, preview, username, author, status, postId);
    }

    public List<Report> reportsForTarget(ReportTargetType type, UUID targetId) {
        CommunityModerationService.requireStaff();
        return reportRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(type, targetId);
    }

    // -----------------------------------------------------------------
    // Pending queue
    // -----------------------------------------------------------------

    public record PendingQueue(List<CommunityPost> posts, List<CommunityPostComment> comments, Map<UUID, User> authors) {
    }

    public PendingQueue pending() {
        CommunityModerationService.requireStaff();
        List<CommunityPost> posts = postRepository.findAll(
                (root, q, cb) -> cb.and(cb.isNull(root.get("deletedAt")),
                        root.get("status").in(CommunityContentStatus.PENDING, CommunityContentStatus.HIDDEN)),
                Sort.by(Sort.Direction.ASC, "createdAt"));
        List<CommunityPostComment> comments = commentRepository.findAll(
                (root, q, cb) -> cb.and(cb.isNull(root.get("deletedAt")),
                        root.get("status").in(CommunityContentStatus.PENDING, CommunityContentStatus.HIDDEN)),
                Sort.by(Sort.Direction.ASC, "createdAt"));
        Set<UUID> ids = new HashSet<>();
        posts.forEach(p -> ids.add(p.getAuthorUserId()));
        comments.forEach(c -> ids.add(c.getAuthorUserId()));
        return new PendingQueue(posts, comments, users(ids));
    }

    // -----------------------------------------------------------------
    // Members
    // -----------------------------------------------------------------

    public record MemberRow(UUID userId, String username, String avatarUrl, Instant accountCreatedAt, long posts, long comments,
                            long karma, long reportsReceived, String status, boolean trusted, boolean moderator) {
    }

    public Page<MemberRow> members(String q, String status, int page) {
        CommunityModerationService.requireStaff();
        String like = q == null || q.isBlank() ? null : "%" + q.trim().replaceFirst("^u/", "").toLowerCase(Locale.ROOT) + "%";
        String statusFilter = status == null || status.isBlank() ? null : status;
        String base = """
                FROM app_user u
                LEFT JOIN LATERAL (SELECT r.type FROM community_restriction r
                                   WHERE r.user_id = u.id AND r.status = 'ACTIVE' AND r.type <> 'WARN'
                                     AND r.starts_at <= now() AND (r.ends_at IS NULL OR r.ends_at > now())
                                   ORDER BY r.created_at DESC LIMIT 1) rs ON TRUE
                WHERE u.community_username IS NOT NULL
                  AND (CAST(? AS varchar) IS NULL OR LOWER(u.community_username) LIKE CAST(? AS varchar))
                  AND (CAST(? AS varchar) IS NULL
                       OR (CAST(? AS varchar) = 'ACTIVE' AND rs.type IS NULL)
                       OR rs.type = CAST(? AS varchar))
                """;
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + base, Long.class, like, like, statusFilter, statusFilter, statusFilter);
        List<MemberRow> rows = jdbc.query("""
                SELECT u.id, u.community_username, u.community_avatar_url, u.created_at, u.community_trusted, u.staff_role,
                       rs.type AS restriction,
                       (SELECT COUNT(*) FROM community_post p WHERE p.author_user_id = u.id AND p.deleted_at IS NULL) AS posts,
                       (SELECT COUNT(*) FROM community_post_comment c WHERE c.author_user_id = u.id AND c.deleted_at IS NULL) AS comments,
                       (SELECT COALESCE(SUM(p.upvote_count - p.downvote_count), 0) FROM community_post p WHERE p.author_user_id = u.id AND p.deleted_at IS NULL)
                     + (SELECT COALESCE(SUM(c.upvote_count - c.downvote_count), 0) FROM community_post_comment c WHERE c.author_user_id = u.id AND c.deleted_at IS NULL) AS karma,
                       (SELECT COUNT(*) FROM report r WHERE
                            (r.target_type = 'COMMUNITY_POST' AND r.target_id IN (SELECT id FROM community_post WHERE author_user_id = u.id))
                         OR (r.target_type = 'COMMUNITY_COMMENT' AND r.target_id IN (SELECT id FROM community_post_comment WHERE author_user_id = u.id))
                         OR (r.target_type = 'COMMUNITY_PROFILE' AND r.target_id = u.community_profile_id)) AS reports
                """ + base + " ORDER BY u.created_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> new MemberRow(rs.getObject("id", UUID.class), rs.getString("community_username"),
                        rs.getString("community_avatar_url"), rs.getTimestamp("created_at").toInstant(),
                        rs.getLong("posts"), rs.getLong("comments"), rs.getLong("karma"), rs.getLong("reports"),
                        rs.getString("restriction") == null ? "ACTIVE" : rs.getString("restriction"),
                        rs.getBoolean("community_trusted"), "MODERATOR".equals(rs.getString("staff_role"))),
                like, like, statusFilter, statusFilter, statusFilter, PAGE_SIZE, (long) Math.max(page, 0) * PAGE_SIZE);
        return new PageImpl<>(rows, PageRequest.of(Math.max(page, 0), PAGE_SIZE), total == null ? 0 : total);
    }

    public MemberRow member(UUID userId) {
        CommunityModerationService.requireStaff();
        User u = userRepository.findById(userId).orElseThrow(() -> new com.bdreview.platform.common.ResourceNotFoundException("User not found"));
        long posts = count("SELECT COUNT(*) FROM community_post WHERE author_user_id = ? AND deleted_at IS NULL", userId);
        long comments = count("SELECT COUNT(*) FROM community_post_comment WHERE author_user_id = ? AND deleted_at IS NULL", userId);
        long karma = count("SELECT COALESCE((SELECT SUM(upvote_count - downvote_count) FROM community_post WHERE author_user_id = ? AND deleted_at IS NULL), 0)"
                + " + COALESCE((SELECT SUM(upvote_count - downvote_count) FROM community_post_comment WHERE author_user_id = ? AND deleted_at IS NULL), 0)", userId, userId);
        long reports = count("""
                SELECT COUNT(*) FROM report r WHERE
                   (r.target_type = 'COMMUNITY_POST' AND r.target_id IN (SELECT id FROM community_post WHERE author_user_id = ?))
                OR (r.target_type = 'COMMUNITY_COMMENT' AND r.target_id IN (SELECT id FROM community_post_comment WHERE author_user_id = ?))
                OR (r.target_type = 'COMMUNITY_PROFILE' AND r.target_id = ?)
                """, userId, userId, u.getCommunityProfileId());
        String status = policy.activeBlockingRestriction(userId).map(r -> r.getType().name()).orElse("ACTIVE");
        return new MemberRow(u.getId(), u.getCommunityUsername(), u.getCommunityAvatarUrl(), u.getCreatedAt(), posts, comments,
                karma, reports, status, u.isCommunityTrusted(), u.isModerator());
    }

    public List<CommunityPost> recentPostsBy(UUID userId) {
        CommunityModerationService.requireStaff();
        return postRepository.findAllByAuthorUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, PageRequest.of(0, 20)).getContent();
    }

    public long removedCount(UUID userId) {
        return count("SELECT (SELECT COUNT(*) FROM community_post WHERE author_user_id = ? AND status = 'REMOVED')"
                + " + (SELECT COUNT(*) FROM community_post_comment WHERE author_user_id = ? AND status = 'REMOVED')", userId, userId);
    }

    public List<CommunityRestriction> restrictions(UUID userId) {
        CommunityModerationService.requireStaff();
        return restrictionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Page<CommunityRestriction> activeRestrictions(int page) {
        CommunityModerationService.requireStaff();
        return restrictionRepository.findAllBlockingInEffect(Instant.now(), PageRequest.of(Math.max(page, 0), PAGE_SIZE));
    }

    // -----------------------------------------------------------------
    // Post detail helpers
    // -----------------------------------------------------------------

    public List<CommunityPostComment> allComments(UUID postId) {
        CommunityModerationService.requireStaff();
        return commentRepository.findAllByPostIdAndDeletedAtIsNullOrderByCreatedAtAsc(postId);
    }

    public List<CommunityPostPhoto> photos(UUID postId) {
        CommunityModerationService.requireStaff();
        return photoRepository.findByPostIdOrderByPositionAsc(postId);
    }

    public List<AuditLog> auditFor(String entityType, UUID id) {
        CommunityModerationService.requireStaff();
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, id, PageRequest.of(0, 50)).getContent();
    }

    // -----------------------------------------------------------------
    // Audit log (ADMIN)
    // -----------------------------------------------------------------

    public record AuditFilter(UUID actor, String action, String entityType, UUID entityId, LocalDate from, LocalDate to) {
    }

    public Page<AuditLog> audit(AuditFilter f, Pageable pageable) {
        CommunityModerationService.requireAdmin();
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (f.actor() != null) {
                ps.add(cb.equal(root.get("performedByAdmin"), f.actor()));
            }
            if (f.action() != null && !f.action().isBlank()) {
                ps.add(cb.like(cb.upper(root.get("action")), "%" + f.action().trim().toUpperCase(Locale.ROOT) + "%"));
            }
            if (f.entityType() != null && !f.entityType().isBlank()) {
                ps.add(cb.equal(root.get("entityType"), f.entityType().trim()));
            }
            if (f.entityId() != null) {
                ps.add(cb.equal(root.get("entityId"), f.entityId()));
            }
            if (f.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from().atStartOfDay(ZONE).toInstant()));
            }
            if (f.to() != null) {
                ps.add(cb.lessThan(root.get("createdAt"), f.to().plusDays(1).atStartOfDay(ZONE).toInstant()));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return auditLogRepository.findAll(spec, pageable);
    }

    // -----------------------------------------------------------------
    // Shared
    // -----------------------------------------------------------------

    public Map<UUID, User> users(Collection<UUID> ids) {
        Map<UUID, User> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        userRepository.findAllById(ids.stream().filter(Objects::nonNull).distinct().toList()).forEach(u -> map.put(u.getId(), u));
        return map;
    }

    private static Optional<UUID> parseUuid(String s) {
        try {
            return Optional.of(UUID.fromString(s.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}

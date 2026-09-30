package com.bdreview.platform.community.moderation;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.common.PageResponse;
import com.bdreview.platform.community.CommunityPost;
import com.bdreview.platform.community.CommunityPostComment;
import com.bdreview.platform.community.CommunityPostType;
import com.bdreview.platform.community.CommunityTopic;
import com.bdreview.platform.community.moderation.CommunityModerationService.BulkPostAction;
import com.bdreview.platform.community.moderation.CommunityModerationService.ReportAction;
import com.bdreview.platform.community.moderation.CommunityModerationService.RestrictionDuration;
import com.bdreview.platform.community.settings.CommunitySettings;
import com.bdreview.platform.community.settings.CommunitySettingsService;
import com.bdreview.platform.community.settings.CommunityTopicService;
import com.bdreview.platform.moderation.AuditLog;
import com.bdreview.platform.report.Report;
import com.bdreview.platform.report.ReportTargetType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * JSON admin API for the community (JWT). Class-level: ADMIN or MODERATOR. ADMIN-only endpoints
 * (settings, topics, announcements, audit, roles, reveal identity, hard delete) carry their own
 * {@code @PreAuthorize}; the services re-check roles too. Same operations as the Thymeleaf admin
 * panel's Community section — both call CommunityModerationService / CommunityAdminQueryService.
 */
@RestController
@RequestMapping("/api/v1/admin/community")
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
public class CommunityAdminApiController {

    private final CommunityAdminQueryService queries;
    private final CommunityModerationService moderation;
    private final CommunitySettingsService settingsService;
    private final CommunityTopicService topicService;
    private final CommunityAnnouncementService announcementService;
    private final UserRepository userRepository;

    public CommunityAdminApiController(CommunityAdminQueryService queries,
                                       CommunityModerationService moderation,
                                       CommunitySettingsService settingsService,
                                       CommunityTopicService topicService,
                                       CommunityAnnouncementService announcementService,
                                       UserRepository userRepository) {
        this.queries = queries;
        this.moderation = moderation;
        this.settingsService = settingsService;
        this.topicService = topicService;
        this.announcementService = announcementService;
        this.userRepository = userRepository;
    }

    /** Generic action body — only the fields an action needs are read. */
    public record ActionRequest(String reason, String scope, Instant until, String topic, CommunityPostType postType,
                                String confirm, Boolean trustUser) {
    }

    public record BulkRequest(List<UUID> ids, BulkPostAction action, String reason) {
    }

    public record ResolveReportsRequest(ReportAction action, RestrictionDuration duration, Instant customEnd, String reason) {
    }

    public record RestrictRequest(CommunityRestriction.Type type, RestrictionDuration duration, Instant customEnd, String reason) {
    }

    public record RoleRequest(String phoneNumber, UUID userId, String reason) {
    }

    // ---- Admin-safe DTOs (never serialise User: it carries the password hash) ----

    public record PostRow(UUID id, String title, String body, String postType, String topic, UUID areaId, String status,
                          String holdReason, boolean locked, boolean pinned, String pinScope, Instant pinnedUntil,
                          boolean featured, boolean official, int upvotes, int downvotes, int comments, int reportCount,
                          String removedReason, Instant removedAt, Instant createdAt, String authorUsername, UUID authorUserId) {
        static PostRow of(CommunityPost p, Map<UUID, User> users) {
            User a = users.get(p.getAuthorUserId());
            return new PostRow(p.getId(), p.getTitle(), p.getBody(), p.getPostType().name(), p.getTopic(), p.getAreaId(),
                    p.getStatus().name(), p.getHoldReason(), p.isLocked(), p.isPinned(), p.getPinScope(), p.getPinnedUntil(),
                    p.isFeatured(), p.isOfficial(), p.getUpvoteCount(), p.getDownvoteCount(), p.getCommentCount(),
                    p.getReportCount(), p.getRemovedReason(), p.getRemovedAt(), p.getCreatedAt(),
                    a == null ? null : a.getCommunityUsername(), p.getAuthorUserId());
        }
    }

    public record CommentRow(UUID id, UUID postId, UUID parentCommentId, int depth, String content, String status,
                             String holdReason, int upvotes, int downvotes, int reportCount, String removedReason,
                             Instant createdAt, String authorUsername, UUID authorUserId) {
        static CommentRow of(CommunityPostComment c, Map<UUID, User> users) {
            User a = users.get(c.getAuthorUserId());
            return new CommentRow(c.getId(), c.getPostId(), c.getParentCommentId(), c.getDepth(), c.getContent(),
                    c.getStatus().name(), c.getHoldReason(), c.getUpvoteCount(), c.getDownvoteCount(), c.getReportCount(),
                    c.getRemovedReason(), c.getCreatedAt(), a == null ? null : a.getCommunityUsername(), c.getAuthorUserId());
        }
    }

    public record ReportRow(UUID id, String referenceCode, String reason, String status, Instant createdAt,
                            Instant resolvedAt, UUID resolvedBy) {
        static ReportRow of(Report r) {
            return new ReportRow(r.getId(), r.getReferenceCode(), r.getReason().name(), r.getStatus().name(),
                    r.getCreatedAt(), r.getResolvedAt(), r.getResolvedBy());
        }
    }

    public record PostDetail(PostRow post, List<Map<String, Object>> images, List<CommentRow> comments,
                             List<ReportRow> reports, List<AuditLog> audit, CommunityAdminQueryService.MemberRow author,
                             long authorRemovals, List<CommunityRestriction> authorRestrictions) {
    }

    // -----------------------------------------------------------------
    // Dashboard
    // -----------------------------------------------------------------

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        var d = queries.dashboard();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stats", d.stats());
        out.put("last30Days", d.last30Days());
        out.put("newestReports", d.newestReports());
        out.put("autoFlagged", d.autoFlagged().stream().map(p -> PostRow.of(p, d.authors())).toList());
        out.put("heavilyDownvoted", d.heavilyDownvoted().stream().map(p -> PostRow.of(p, d.authors())).toList());
        return out;
    }

    // -----------------------------------------------------------------
    // Posts
    // -----------------------------------------------------------------

    @GetMapping("/posts")
    public PageResponse<PostRow> posts(@RequestParam(required = false) String q, @RequestParam(required = false) String type,
                                       @RequestParam(required = false) String topic, @RequestParam(required = false) UUID areaId,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(required = false) Boolean reported, @RequestParam(required = false) Boolean hasImages,
                                       @RequestParam(required = false) String sort, @RequestParam(defaultValue = "0") int page) {
        var result = queries.posts(new CommunityAdminQueryService.PostFilter(q, type, topic, areaId, status, from, to, reported, hasImages, sort), page);
        Map<UUID, User> users = queries.users(result.getContent().stream().map(CommunityPost::getAuthorUserId).toList());
        return PageResponse.of(result.map(p -> PostRow.of(p, users)));
    }

    @GetMapping("/posts/{postId}")
    public PostDetail post(@PathVariable UUID postId) {
        CommunityPost post = moderation.requirePost(postId);
        List<CommunityPostComment> comments = queries.allComments(postId);
        Set<UUID> ids = new HashSet<>();
        ids.add(post.getAuthorUserId());
        comments.forEach(c -> ids.add(c.getAuthorUserId()));
        Map<UUID, User> users = queries.users(ids);
        List<Map<String, Object>> images = queries.photos(postId).stream().map(ph -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ph.getId());
            m.put("url", ph.getUrl());
            m.put("removed", ph.getRemovedAt() != null);
            m.put("removedReason", ph.getRemovedReason());
            return m;
        }).toList();
        return new PostDetail(PostRow.of(post, users), images,
                comments.stream().map(c -> CommentRow.of(c, users)).toList(),
                queries.reportsForTarget(ReportTargetType.COMMUNITY_POST, postId).stream().map(ReportRow::of).toList(),
                queries.auditFor("COMMUNITY_POST", postId),
                queries.member(post.getAuthorUserId()),
                queries.removedCount(post.getAuthorUserId()),
                queries.restrictions(post.getAuthorUserId()));
    }

    @PostMapping("/posts/{postId}/{action}")
    public ResponseEntity<Void> postAction(@PathVariable UUID postId, @PathVariable String action,
                                           @RequestBody(required = false) ActionRequest body) {
        ActionRequest b = body == null ? new ActionRequest(null, null, null, null, null, null, null) : body;
        switch (action) {
            case "hide" -> moderation.hidePost(postId, b.reason());
            case "remove" -> moderation.removePost(postId, b.reason());
            case "restore" -> moderation.restorePost(postId, b.reason());
            case "lock" -> moderation.setLocked(postId, true, b.reason());
            case "unlock" -> moderation.setLocked(postId, false, b.reason());
            case "pin" -> moderation.pin(postId, b.scope(), b.until(), b.reason());
            case "unpin" -> moderation.unpin(postId, b.reason());
            case "feature" -> moderation.setFeatured(postId, true, b.reason());
            case "unfeature" -> moderation.setFeatured(postId, false, b.reason());
            case "topic" -> moderation.changeTopic(postId, b.topic(), b.reason());
            case "type" -> moderation.changePostType(postId, b.postType(), b.reason());
            case "approve" -> moderation.approvePost(postId, Boolean.TRUE.equals(b.trustUser()), b.reason());
            case "reject" -> moderation.rejectPost(postId, b.reason());
            default -> {
                return ResponseEntity.notFound().build();
            }
        }
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<Void> hardDelete(@PathVariable UUID postId, @RequestBody ActionRequest body) {
        moderation.hardDeletePost(postId, body.reason(), body.confirm());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/bulk")
    public Map<String, Integer> bulk(@RequestBody BulkRequest body) {
        return Map.of("updated", moderation.bulkPosts(body.ids(), body.action(), body.reason()));
    }

    @PostMapping("/images/{photoId}/{action}")
    public ResponseEntity<Void> imageAction(@PathVariable UUID photoId, @PathVariable String action,
                                            @RequestBody(required = false) ActionRequest body) {
        String reason = body == null ? null : body.reason();
        if ("remove".equals(action)) {
            moderation.removePhoto(photoId, reason);
        } else if ("restore".equals(action)) {
            moderation.restorePhoto(photoId, reason);
        } else {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------
    // Comments
    // -----------------------------------------------------------------

    @GetMapping("/comments")
    public PageResponse<CommentRow> comments(@RequestParam(required = false) String q, @RequestParam(required = false) UUID postId,
                                             @RequestParam(required = false) String username, @RequestParam(required = false) String status,
                                             @RequestParam(required = false) Boolean reported, @RequestParam(defaultValue = "0") int page) {
        var result = queries.comments(new CommunityAdminQueryService.CommentFilter(q, postId, username, status, reported), page);
        Map<UUID, User> users = queries.users(result.getContent().stream().map(CommunityPostComment::getAuthorUserId).toList());
        return PageResponse.of(result.map(c -> CommentRow.of(c, users)));
    }

    @PostMapping("/comments/{commentId}/{action}")
    public ResponseEntity<Void> commentAction(@PathVariable UUID commentId, @PathVariable String action,
                                              @RequestBody(required = false) ActionRequest body) {
        ActionRequest b = body == null ? new ActionRequest(null, null, null, null, null, null, null) : body;
        switch (action) {
            case "remove" -> moderation.removeComment(commentId, b.reason());
            case "restore" -> moderation.restoreComment(commentId, b.reason());
            case "approve" -> moderation.approveComment(commentId, Boolean.TRUE.equals(b.trustUser()), b.reason());
            case "reject" -> moderation.rejectComment(commentId, b.reason());
            default -> {
                return ResponseEntity.notFound().build();
            }
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{postId}/comments/remove-by-user/{userId}")
    public Map<String, Integer> removeCommentsByUser(@PathVariable UUID postId, @PathVariable UUID userId,
                                                     @RequestBody ActionRequest body) {
        return Map.of("removed", moderation.removeCommentsByUserOnPost(postId, userId, body.reason()));
    }

    // -----------------------------------------------------------------
    // Reports
    // -----------------------------------------------------------------

    @GetMapping("/reports")
    public PageResponse<CommunityAdminQueryService.ReportGroup> reports(@RequestParam(required = false) String type,
                                                                        @RequestParam(defaultValue = "0") int page) {
        return PageResponse.of(queries.reportGroups(type, PageRequest.of(Math.max(page, 0), CommunityAdminQueryService.PAGE_SIZE)));
    }

    @GetMapping("/reports/{targetType}/{targetId}")
    public List<ReportRow> reportsForTarget(@PathVariable ReportTargetType targetType, @PathVariable UUID targetId) {
        return queries.reportsForTarget(targetType, targetId).stream().map(ReportRow::of).toList();
    }

    @PostMapping("/reports/{targetType}/{targetId}/resolve")
    public Map<String, Integer> resolveReports(@PathVariable ReportTargetType targetType, @PathVariable UUID targetId,
                                               @RequestBody ResolveReportsRequest body) {
        return Map.of("resolved", moderation.resolveReportsForTarget(targetType, targetId, body.action(),
                body.duration(), body.customEnd(), body.reason()));
    }

    // -----------------------------------------------------------------
    // Pending queue
    // -----------------------------------------------------------------

    @GetMapping("/queue")
    public Map<String, Object> queue() {
        var q = queries.pending();
        return Map.of("posts", q.posts().stream().map(p -> PostRow.of(p, q.authors())).toList(),
                "comments", q.comments().stream().map(c -> CommentRow.of(c, q.authors())).toList());
    }

    // -----------------------------------------------------------------
    // Members + restrictions
    // -----------------------------------------------------------------

    @GetMapping("/users")
    public PageResponse<CommunityAdminQueryService.MemberRow> users(@RequestParam(required = false) String q,
                                                                    @RequestParam(required = false) String status,
                                                                    @RequestParam(defaultValue = "0") int page) {
        return PageResponse.of(queries.members(q, status, page));
    }

    @GetMapping("/users/{userId}")
    public Map<String, Object> user(@PathVariable UUID userId) {
        return Map.of("member", queries.member(userId), "restrictions", queries.restrictions(userId),
                "removedItems", queries.removedCount(userId));
    }

    @GetMapping("/restrictions")
    public PageResponse<CommunityRestriction> activeRestrictions(@RequestParam(defaultValue = "0") int page) {
        return PageResponse.of(queries.activeRestrictions(page));
    }

    @PostMapping("/users/{userId}/restrictions")
    public CommunityRestriction restrict(@PathVariable UUID userId, @RequestBody RestrictRequest body) {
        return moderation.restrict(userId, body.type(), body.duration(), body.customEnd(), body.reason());
    }

    @PostMapping("/restrictions/{restrictionId}/lift")
    public ResponseEntity<Void> lift(@PathVariable UUID restrictionId, @RequestBody ActionRequest body) {
        moderation.liftRestriction(restrictionId, body.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{userId}/{action}")
    public Map<String, Object> userAction(@PathVariable UUID userId, @PathVariable String action, @RequestBody ActionRequest body) {
        return switch (action) {
            case "remove-content" -> Map.of("removed", moderation.removeAllContent(userId, body.reason()));
            case "reset-avatar" -> {
                moderation.resetAvatar(userId, body.reason());
                yield Map.of("ok", true);
            }
            case "reset-username" -> Map.of("communityUsername", moderation.resetUsername(userId, body.reason()));
            case "trust" -> {
                moderation.trustUser(userId, body.reason());
                yield Map.of("ok", true);
            }
            default -> throw new com.bdreview.platform.common.ResourceNotFoundException("No such action");
        };
    }

    /** ADMIN only: the real account behind a community username. Reason required; audit-logged. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/users/{userId}/reveal")
    public Map<String, Object> reveal(@PathVariable UUID userId, @RequestBody ActionRequest body) {
        return moderation.revealIdentity(userId, body.reason());
    }

    // -----------------------------------------------------------------
    // Settings / topics / announcements (ADMIN)
    // -----------------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/settings")
    public CommunitySettings settings() {
        return settingsService.loadStoredSettings();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/settings")
    public CommunitySettings saveSettings(@RequestBody CommunitySettings body, @RequestParam(required = false) String reason) {
        return settingsService.save(body, reason);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/topics")
    public List<CommunityTopic> topics() {
        return topicService.list();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/topics")
    public CommunityTopic createTopic(@RequestBody CommunityTopicService.TopicInput body) {
        return topicService.create(body);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/topics/{code}")
    public CommunityTopic updateTopic(@PathVariable String code, @RequestBody CommunityTopicService.TopicInput body) {
        return topicService.update(code, body);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/topics/reorder")
    public ResponseEntity<Void> reorderTopics(@RequestBody List<String> codes) {
        topicService.reorder(codes);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/announcements")
    public List<Map<String, Object>> announcements() {
        return announcementService.list().stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("announcement", v.announcement());
            m.put("title", v.post().getTitle());
            m.put("body", v.post().getBody());
            m.put("live", v.live());
            return m;
        }).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/announcements")
    public CommunityAnnouncement createAnnouncement(@RequestBody CommunityAnnouncementService.AnnouncementInput body) {
        return announcementService.create(body).announcement();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/announcements/{id}")
    public CommunityAnnouncement updateAnnouncement(@PathVariable UUID id, @RequestBody CommunityAnnouncementService.AnnouncementInput body) {
        return announcementService.update(id, body).announcement();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/announcements/{id}/end")
    public ResponseEntity<Void> endAnnouncement(@PathVariable UUID id, @RequestBody(required = false) ActionRequest body) {
        announcementService.end(id, body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------
    // Audit log (ADMIN, read-only) + CSV
    // -----------------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/audit")
    public PageResponse<AuditLog> audit(@RequestParam(required = false) UUID actor, @RequestParam(required = false) String action,
                                        @RequestParam(required = false) String targetType, @RequestParam(required = false) UUID targetId,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(defaultValue = "0") int page) {
        return PageResponse.of(queries.audit(new CommunityAdminQueryService.AuditFilter(actor, action, targetType, targetId, from, to),
                PageRequest.of(Math.max(page, 0), 50, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping(value = "/audit.csv", produces = "text/csv")
    public ResponseEntity<byte[]> auditCsv(@RequestParam(required = false) UUID actor, @RequestParam(required = false) String action,
                                           @RequestParam(required = false) String targetType, @RequestParam(required = false) UUID targetId,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var rows = queries.audit(new CommunityAdminQueryService.AuditFilter(actor, action, targetType, targetId, from, to),
                PageRequest.of(0, 10000, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        return csvResponse(AuditCsv.write(rows));
    }

    public static ResponseEntity<byte[]> csvResponse(String csv) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-log.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(("﻿" + csv).getBytes(StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------
    // Roles (ADMIN)
    // -----------------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/roles")
    public List<Map<String, Object>> moderators() {
        return userRepository.findAllByStaffRoleOrderByNameAsc(User.STAFF_MODERATOR).stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", u.getId());
            m.put("name", u.getName());
            m.put("phoneNumber", u.getPhoneNumber());
            m.put("communityUsername", u.getCommunityUsername());
            m.put("accountType", u.getRole().name());
            return m;
        }).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/roles/moderators")
    public Map<String, Object> assignModerator(@RequestBody RoleRequest body) {
        User u = moderation.assignModerator(body.phoneNumber(), body.userId(), body.reason());
        return Map.of("userId", u.getId(), "staffRole", String.valueOf(u.getStaffRole()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/roles/moderators/{userId}")
    public ResponseEntity<Void> removeModerator(@PathVariable UUID userId, @RequestParam(required = false) String reason) {
        moderation.removeModerator(userId, reason);
        return ResponseEntity.noContent().build();
    }
}

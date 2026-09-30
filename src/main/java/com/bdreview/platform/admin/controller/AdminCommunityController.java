package com.bdreview.platform.admin.controller;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.business.Area;
import com.bdreview.platform.business.AreaRepository;
import com.bdreview.platform.community.*;
import com.bdreview.platform.community.moderation.*;
import com.bdreview.platform.community.moderation.CommunityModerationService.BulkPostAction;
import com.bdreview.platform.community.moderation.CommunityModerationService.ReportAction;
import com.bdreview.platform.community.moderation.CommunityModerationService.RestrictionDuration;
import com.bdreview.platform.community.settings.CommunitySettings;
import com.bdreview.platform.community.settings.CommunitySettingsService;
import com.bdreview.platform.community.settings.CommunityTopicService;
import com.bdreview.platform.community.settings.FeatureFlagService;
import com.bdreview.platform.report.ReportTargetType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Admin panel → Community section (Thymeleaf, session auth). ADMIN or MODERATOR at class level;
 * settings / topics / announcements / roles / audit / reveal identity / hard delete are ADMIN
 * only (method-level @PreAuthorize + AdminSecurityConfig URL rules + a service-level check).
 * All writes go through CommunityModerationService, which audit-logs every action.
 */
@Controller
@RequestMapping("/admin/community")
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
public class AdminCommunityController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");

    private final CommunityAdminQueryService queries;
    private final CommunityModerationService moderation;
    private final CommunitySettingsService settingsService;
    private final CommunityTopicService topicService;
    private final CommunityAnnouncementService announcementService;
    private final FeatureFlagService featureFlags;
    private final UserRepository userRepository;
    private final AreaRepository areaRepository;

    public AdminCommunityController(CommunityAdminQueryService queries, CommunityModerationService moderation,
                                    CommunitySettingsService settingsService, CommunityTopicService topicService,
                                    CommunityAnnouncementService announcementService, FeatureFlagService featureFlags,
                                    UserRepository userRepository, AreaRepository areaRepository) {
        this.queries = queries;
        this.moderation = moderation;
        this.settingsService = settingsService;
        this.topicService = topicService;
        this.announcementService = announcementService;
        this.featureFlags = featureFlags;
        this.userRepository = userRepository;
        this.areaRepository = areaRepository;
    }

    /** Reason quick-picks for every moderation form (a <datalist>). */
    @ModelAttribute("reasonTemplates")
    public List<String> reasonTemplates() {
        return settingsService.settings().getReasonTemplates();
    }

    /** Current filters as a query-string prefix ("a=1&b=2&") for the pager's Previous/Next links. */
    @ModelAttribute("pageQuery")
    public String pageQuery(jakarta.servlet.http.HttpServletRequest request) {
        String qs = request.getQueryString();
        if (qs == null || qs.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : qs.split("&")) {
            if (!part.startsWith("page=") && !part.isBlank()) {
                sb.append(part).append('&');
            }
        }
        return sb.toString();
    }

    /** Current path + query, so action forms can send the moderator back to the same filtered list. */
    @ModelAttribute("currentUrl")
    public String currentUrl(jakarta.servlet.http.HttpServletRequest request) {
        String qs = request.getQueryString();
        return request.getRequestURI() + (qs == null ? "" : "?" + qs);
    }

    @ModelAttribute("frontendUrl")
    public String frontendUrl(@org.springframework.beans.factory.annotation.Value("${app.frontend-url:http://localhost:3000}") String url) {
        return url;
    }

    @ModelAttribute("topics")
    public List<com.bdreview.platform.community.settings.TopicView> topics() {
        return settingsService.config().topics();
    }

    // -----------------------------------------------------------------
    // A. Dashboard
    // -----------------------------------------------------------------

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("d", queries.dashboard());
        model.addAttribute("active", "c-dashboard");
        return "admin/community/dashboard";
    }

    // -----------------------------------------------------------------
    // B. Posts
    // -----------------------------------------------------------------

    @GetMapping("/posts")
    public String posts(@RequestParam(required = false) String q, @RequestParam(required = false) String type,
                        @RequestParam(required = false) String topic, @RequestParam(required = false) UUID areaId,
                        @RequestParam(required = false) String status,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(required = false) Boolean reported, @RequestParam(required = false) Boolean hasImages,
                        @RequestParam(required = false) String sort, @RequestParam(defaultValue = "0") int page, Model model) {
        var filter = new CommunityAdminQueryService.PostFilter(q, blank(type), blank(topic), areaId, blank(status), from, to, reported, hasImages, sort);
        var results = queries.posts(filter, page);
        model.addAttribute("results", results);
        model.addAttribute("authors", queries.users(results.getContent().stream().map(CommunityPost::getAuthorUserId).toList()));
        model.addAttribute("f", filter);
        model.addAttribute("areas", areaRepository.findAllWithCity());
        model.addAttribute("active", "c-posts");
        return "admin/community/posts";
    }

    @PostMapping("/posts/bulk")
    public String bulk(@RequestParam(name = "ids", required = false) List<UUID> ids, @RequestParam BulkPostAction action,
                       @RequestParam(required = false) String reason, @RequestParam(required = false) String back,
                       RedirectAttributes ra) {
        run(ra, () -> moderation.bulkPosts(ids, action, reason) + " post(s) updated: " + action.name().toLowerCase(Locale.ROOT));
        return redirect(back, "/admin/community/posts");
    }

    @GetMapping("/posts/{postId}")
    public String post(@PathVariable UUID postId, Model model) {
        CommunityPost post = moderation.requirePost(postId);
        List<CommunityPostComment> comments = queries.allComments(postId);
        Set<UUID> ids = new HashSet<>();
        ids.add(post.getAuthorUserId());
        comments.forEach(c -> ids.add(c.getAuthorUserId()));
        model.addAttribute("post", post);
        model.addAttribute("photos", queries.photos(postId));
        model.addAttribute("comments", comments);
        model.addAttribute("commenterIds", comments.stream().map(CommunityPostComment::getAuthorUserId).distinct().toList());
        model.addAttribute("users", queries.users(ids));
        model.addAttribute("author", queries.member(post.getAuthorUserId()));
        model.addAttribute("authorRemovals", queries.removedCount(post.getAuthorUserId()));
        model.addAttribute("authorRestrictions", queries.restrictions(post.getAuthorUserId()));
        model.addAttribute("reports", queries.reportsForTarget(ReportTargetType.COMMUNITY_POST, postId));
        model.addAttribute("audit", queries.auditFor("COMMUNITY_POST", postId));
        model.addAttribute("postTypes", List.of(CommunityPostType.DISCUSSION, CommunityPostType.QUESTION, CommunityPostType.RECOMMENDATION));
        model.addAttribute("active", "c-posts");
        return "admin/community/post";
    }

    @PostMapping("/posts/{postId}/action")
    public String postAction(@PathVariable UUID postId, @RequestParam String action,
                             @RequestParam(required = false) String reason, @RequestParam(required = false) String scope,
                             @RequestParam(required = false) String until, @RequestParam(required = false) String topic,
                             @RequestParam(required = false) CommunityPostType postType,
                             @RequestParam(required = false) String back, RedirectAttributes ra) {
        run(ra, () -> {
            switch (action) {
                case "hide" -> moderation.hidePost(postId, reason);
                case "remove" -> moderation.removePost(postId, reason);
                case "restore" -> moderation.restorePost(postId, reason);
                case "lock" -> moderation.setLocked(postId, true, reason);
                case "unlock" -> moderation.setLocked(postId, false, reason);
                case "pin" -> moderation.pin(postId, scope, parseLocal(until), reason);
                case "unpin" -> moderation.unpin(postId, reason);
                case "feature" -> moderation.setFeatured(postId, true, reason);
                case "unfeature" -> moderation.setFeatured(postId, false, reason);
                case "topic" -> moderation.changeTopic(postId, topic, reason);
                case "type" -> moderation.changePostType(postId, postType, reason);
                case "approve" -> moderation.approvePost(postId, false, reason);
                case "approve-trust" -> moderation.approvePost(postId, true, reason);
                case "reject" -> moderation.rejectPost(postId, reason);
                default -> throw new IllegalArgumentException("Unknown action");
            }
            return "Post updated (" + action + ").";
        });
        return redirect(back, "/admin/community/posts/" + postId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/posts/{postId}/hard-delete")
    public String hardDelete(@PathVariable UUID postId, @RequestParam String reason, @RequestParam String confirm,
                             RedirectAttributes ra) {
        try {
            moderation.hardDeletePost(postId, reason, confirm);
            ra.addFlashAttribute("successMessage", "Post permanently deleted.");
            return "redirect:/admin/community/posts";
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/community/posts/" + postId;
        }
    }

    @PostMapping("/images/{photoId}/{action}")
    public String image(@PathVariable UUID photoId, @PathVariable String action, @RequestParam UUID postId,
                        @RequestParam(required = false) String reason, RedirectAttributes ra) {
        run(ra, () -> {
            if ("remove".equals(action)) {
                moderation.removePhoto(photoId, reason);
            } else {
                moderation.restorePhoto(photoId, reason);
            }
            return "Image " + ("remove".equals(action) ? "removed" : "restored") + ".";
        });
        return "redirect:/admin/community/posts/" + postId;
    }

    @PostMapping("/posts/{postId}/comments/remove-by-user")
    public String removeByUser(@PathVariable UUID postId, @RequestParam UUID userId, @RequestParam String reason,
                               RedirectAttributes ra) {
        run(ra, () -> moderation.removeCommentsByUserOnPost(postId, userId, reason) + " comment(s) removed.");
        return "redirect:/admin/community/posts/" + postId;
    }

    // -----------------------------------------------------------------
    // C. Comments
    // -----------------------------------------------------------------

    @GetMapping("/comments")
    public String comments(@RequestParam(required = false) String q, @RequestParam(required = false) UUID postId,
                           @RequestParam(required = false) String username, @RequestParam(required = false) String status,
                           @RequestParam(required = false) Boolean reported, @RequestParam(defaultValue = "0") int page, Model model) {
        var filter = new CommunityAdminQueryService.CommentFilter(q, postId, username, blank(status), reported);
        var results = queries.comments(filter, page);
        model.addAttribute("results", results);
        model.addAttribute("users", queries.users(results.getContent().stream().map(CommunityPostComment::getAuthorUserId).toList()));
        model.addAttribute("f", filter);
        model.addAttribute("active", "c-comments");
        return "admin/community/comments";
    }

    @PostMapping("/comments/{commentId}/action")
    public String commentAction(@PathVariable UUID commentId, @RequestParam String action,
                                @RequestParam(required = false) String reason, @RequestParam(required = false) String back,
                                RedirectAttributes ra) {
        run(ra, () -> {
            switch (action) {
                case "remove" -> moderation.removeComment(commentId, reason);
                case "restore" -> moderation.restoreComment(commentId, reason);
                case "approve" -> moderation.approveComment(commentId, false, reason);
                case "approve-trust" -> moderation.approveComment(commentId, true, reason);
                case "reject" -> moderation.rejectComment(commentId, reason);
                default -> throw new IllegalArgumentException("Unknown action");
            }
            return "Comment updated (" + action + ").";
        });
        return redirect(back, "/admin/community/comments");
    }

    // -----------------------------------------------------------------
    // D. Reports queue
    // -----------------------------------------------------------------

    @GetMapping("/reports")
    public String reports(@RequestParam(required = false) String type, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("results", queries.reportGroups(blank(type),
                PageRequest.of(Math.max(page, 0), CommunityAdminQueryService.PAGE_SIZE)));
        model.addAttribute("type", type);
        model.addAttribute("active", "c-reports");
        return "admin/community/reports";
    }

    @PostMapping("/reports/resolve")
    public String resolveReports(@RequestParam ReportTargetType targetType, @RequestParam UUID targetId,
                                 @RequestParam ReportAction action, @RequestParam(required = false) RestrictionDuration duration,
                                 @RequestParam(required = false) String customEnd, @RequestParam(required = false) String reason,
                                 RedirectAttributes ra) {
        run(ra, () -> moderation.resolveReportsForTarget(targetType, targetId, action, duration, parseLocal(customEnd), reason)
                + " report(s) closed (" + action.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ").");
        return "redirect:/admin/community/reports";
    }

    // -----------------------------------------------------------------
    // F. Pending approval queue
    // -----------------------------------------------------------------

    @GetMapping("/pending")
    public String pending(Model model) {
        model.addAttribute("q", queries.pending());
        model.addAttribute("active", "c-pending");
        return "admin/community/pending";
    }

    // -----------------------------------------------------------------
    // E. Members
    // -----------------------------------------------------------------

    @GetMapping("/users")
    public String users(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                        @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("results", queries.members(q, blank(status), page));
        model.addAttribute("q", q);
        model.addAttribute("status", status);
        model.addAttribute("active", "c-users");
        return "admin/community/users";
    }

    @GetMapping("/users/{userId}")
    public String user(@PathVariable UUID userId, Model model) {
        model.addAttribute("m", queries.member(userId));
        model.addAttribute("restrictions", queries.restrictions(userId));
        model.addAttribute("removedItems", queries.removedCount(userId));
        model.addAttribute("audit", queries.auditFor("COMMUNITY_USER", userId));
        model.addAttribute("recentPosts", queries.recentPostsBy(userId));
        model.addAttribute("active", "c-users");
        return "admin/community/user";
    }

    @PostMapping("/users/{userId}/restrict")
    public String restrict(@PathVariable UUID userId, @RequestParam CommunityRestriction.Type type,
                           @RequestParam(required = false) RestrictionDuration duration,
                           @RequestParam(required = false) String customEnd, @RequestParam String reason,
                           RedirectAttributes ra) {
        run(ra, () -> {
            moderation.restrict(userId, type, duration, parseLocal(customEnd), reason);
            return "Restriction applied: " + type.name().toLowerCase(Locale.ROOT) + ".";
        });
        return "redirect:/admin/community/users/" + userId;
    }

    @PostMapping("/restrictions/{restrictionId}/lift")
    public String lift(@PathVariable UUID restrictionId, @RequestParam UUID userId, @RequestParam String reason,
                       RedirectAttributes ra) {
        run(ra, () -> {
            moderation.liftRestriction(restrictionId, reason);
            return "Restriction lifted.";
        });
        return "redirect:/admin/community/users/" + userId;
    }

    @PostMapping("/users/{userId}/action")
    public String userAction(@PathVariable UUID userId, @RequestParam String action, @RequestParam String reason,
                             RedirectAttributes ra) {
        run(ra, () -> switch (action) {
            case "remove-content" -> moderation.removeAllContent(userId, reason) + " item(s) removed.";
            case "reset-avatar" -> {
                moderation.resetAvatar(userId, reason);
                yield "Avatar reset.";
            }
            case "reset-username" -> "Username reset to " + moderation.resetUsername(userId, reason) + ".";
            case "trust" -> {
                moderation.trustUser(userId, reason);
                yield "Member marked as trusted.";
            }
            default -> throw new IllegalArgumentException("Unknown action");
        });
        return "redirect:/admin/community/users/" + userId;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/users/{userId}/reveal")
    public String reveal(@PathVariable UUID userId, @RequestParam String reason, RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("revealed", moderation.revealIdentity(userId, reason));
            ra.addFlashAttribute("infoMessage", "Identity revealed — this was recorded in the audit log.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/community/users/" + userId;
    }

    // -----------------------------------------------------------------
    // G. Settings + topics (ADMIN)
    // -----------------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/settings")
    public String settings(Model model) {
        if (!model.containsAttribute("settings")) {
            model.addAttribute("settings", settingsService.loadStoredSettings());
        }
        model.addAttribute("allTopics", topicService.list());
        model.addAttribute("areas", areaRepository.findAllWithCity());
        model.addAttribute("nidDefault", featureFlags.nidVerificationDefault());
        model.addAttribute("nidEffective", featureFlags.nidVerificationEnabled());
        model.addAttribute("active", "c-settings");
        return "admin/community/settings";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/settings")
    public String saveSettings(@ModelAttribute("settings") CommunitySettings settings,
                               @RequestParam(required = false) String bannedWordsText,
                               @RequestParam(required = false) String reasonTemplatesText,
                               @RequestParam(required = false) List<UUID> allowedAreaIds,
                               @RequestParam(required = false) String nidMode,
                               @RequestParam(required = false) String changeReason,
                               RedirectAttributes ra) {
        settings.getContent().setBannedWords(lines(bannedWordsText, true));
        settings.setReasonTemplates(lines(reasonTemplatesText, false));
        settings.getAreas().setAllowedAreaIds(allowedAreaIds == null ? new ArrayList<>() : new ArrayList<>(allowedAreaIds));
        settings.getFeatures().setNidVerificationEnabled(
                "on".equals(nidMode) ? Boolean.TRUE : "off".equals(nidMode) ? Boolean.FALSE : null);
        try {
            settingsService.save(settings, changeReason);
            ra.addFlashAttribute("successMessage", "Community settings saved — they apply to the next request.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            ra.addFlashAttribute("settings", settings);
        }
        return "redirect:/admin/community/settings";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/topics")
    public String createTopic(@RequestParam String code, @RequestParam String label, @RequestParam(required = false) String labelBn,
                              @RequestParam(required = false) String icon, @RequestParam(required = false) String color,
                              RedirectAttributes ra) {
        run(ra, () -> {
            topicService.create(new CommunityTopicService.TopicInput(code, label, labelBn, icon, color, true, false));
            return "Topic created.";
        });
        return "redirect:/admin/community/settings#topics";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/topics/{code}")
    public String updateTopic(@PathVariable String code, @RequestParam String label, @RequestParam(required = false) String labelBn,
                              @RequestParam(required = false) String icon, @RequestParam(required = false) String color,
                              @RequestParam(defaultValue = "false") boolean enabled,
                              @RequestParam(defaultValue = "false") boolean defaultTopic, RedirectAttributes ra) {
        run(ra, () -> {
            topicService.update(code, new CommunityTopicService.TopicInput(code, label, labelBn, icon, color, enabled,
                    defaultTopic ? Boolean.TRUE : null));
            return "Topic " + code + " saved.";
        });
        return "redirect:/admin/community/settings#topics";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/topics/{code}/move")
    public String moveTopic(@PathVariable String code, @RequestParam int delta, RedirectAttributes ra) {
        run(ra, () -> {
            topicService.move(code, delta);
            return "Topic order updated.";
        });
        return "redirect:/admin/community/settings#topics";
    }

    // -----------------------------------------------------------------
    // H. Announcements (ADMIN)
    // -----------------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/announcements")
    public String announcements(Model model) {
        model.addAttribute("items", announcementService.list());
        model.addAttribute("areas", areaRepository.findAllWithCity());
        model.addAttribute("active", "c-announcements");
        return "admin/community/announcements";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/announcements")
    public String createAnnouncement(@RequestParam String title, @RequestParam String body,
                                     @RequestParam CommunityAnnouncement.Scope scope, @RequestParam(required = false) UUID areaId,
                                     @RequestParam(required = false) String topic, @RequestParam(required = false) String startsAt,
                                     @RequestParam(required = false) String endsAt,
                                     @RequestParam(defaultValue = "false") boolean showBanner,
                                     @RequestParam(required = false) String bannerText, RedirectAttributes ra) {
        run(ra, () -> {
            announcementService.create(new CommunityAnnouncementService.AnnouncementInput(title, body, scope, areaId, topic,
                    parseLocal(startsAt), parseLocal(endsAt), showBanner, bannerText));
            return "Announcement published.";
        });
        return "redirect:/admin/community/announcements";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/announcements/{id}/end")
    public String endAnnouncement(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        run(ra, () -> {
            announcementService.end(id, reason);
            return "Announcement ended.";
        });
        return "redirect:/admin/community/announcements";
    }

    // -----------------------------------------------------------------
    // Roles (ADMIN)
    // -----------------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/roles")
    public String roles(Model model) {
        model.addAttribute("moderators", userRepository.findAllByStaffRoleOrderByNameAsc(User.STAFF_MODERATOR));
        model.addAttribute("active", "c-roles");
        return "admin/community/roles";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/roles")
    public String assign(@RequestParam String phoneNumber, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        run(ra, () -> {
            User u = moderation.assignModerator(phoneNumber, null, reason);
            return (u.getName() != null ? u.getName() : u.getPhoneNumber()) + " is now a community moderator.";
        });
        return "redirect:/admin/community/roles";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/roles/{userId}/remove")
    public String removeRole(@PathVariable UUID userId, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        run(ra, () -> {
            moderation.removeModerator(userId, reason);
            return "Moderator role removed.";
        });
        return "redirect:/admin/community/roles";
    }

    // -----------------------------------------------------------------
    // I. Audit log (ADMIN, read-only)
    // -----------------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/audit")
    public String audit(@RequestParam(required = false) UUID actor, @RequestParam(required = false) String action,
                        @RequestParam(required = false) String targetType, @RequestParam(required = false) UUID targetId,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(defaultValue = "0") int page, Model model) {
        var filter = new CommunityAdminQueryService.AuditFilter(actor, action, blank(targetType), targetId, from, to);
        var results = queries.audit(filter, PageRequest.of(Math.max(page, 0), 50, Sort.by(Sort.Direction.DESC, "createdAt")));
        model.addAttribute("results", results);
        model.addAttribute("actors", queries.users(results.getContent().stream().map(a -> a.getPerformedByAdmin()).toList()));
        model.addAttribute("f", filter);
        model.addAttribute("active", "c-audit");
        return "admin/community/audit";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/audit.csv")
    @ResponseBody
    public ResponseEntity<byte[]> auditCsv(@RequestParam(required = false) UUID actor, @RequestParam(required = false) String action,
                                           @RequestParam(required = false) String targetType, @RequestParam(required = false) UUID targetId,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var rows = queries.audit(new CommunityAdminQueryService.AuditFilter(actor, action, blank(targetType), targetId, from, to),
                PageRequest.of(0, 10000, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        return CommunityAdminApiController.csvResponse(AuditCsv.write(rows));
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static void run(RedirectAttributes ra, Supplier<String> action) {
        try {
            ra.addFlashAttribute("successMessage", action.get());
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage() == null ? "Something went wrong" : ex.getMessage());
        }
    }

    /** Only same-section relative paths — never an open redirect. */
    private static String redirect(String back, String fallback) {
        if (back != null && back.startsWith("/admin/community") && !back.contains("//") && !back.contains("\\")) {
            return "redirect:" + back;
        }
        return "redirect:" + fallback;
    }

    private static Instant parseLocal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(value).atZone(ZONE).toInstant();
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static List<String> lines(String text, boolean splitCommas) {
        if (text == null) {
            return new ArrayList<>();
        }
        String[] parts = splitCommas ? text.split("[\\r\\n,]+") : text.split("[\\r\\n]+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }
}

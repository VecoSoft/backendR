package com.bdreview.platform.community.moderation;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.community.*;
import com.bdreview.platform.community.settings.CommunitySettingsService;
import com.bdreview.platform.moderation.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * "Jachai Team" announcements (ADMIN only): an official, pinned community post targeted at
 * everyone / one area / one topic, optionally scheduled (start/end) and optionally surfaced as a
 * dismissible banner across the community (see PublicCommunitySettings#banner).
 */
@Service
public class CommunityAnnouncementService {

    public record AnnouncementInput(String title, String body, CommunityAnnouncement.Scope scope, UUID areaId,
                                    String topic, Instant startsAt, Instant endsAt, boolean showBanner, String bannerText) {
    }

    public record AnnouncementView(CommunityAnnouncement announcement, CommunityPost post, boolean live) {
    }

    /** What the public settings endpoint exposes: the newest live announcement that asked for a banner. */
    public record Banner(UUID id, UUID postId, String text, String scope, UUID areaId, String topic) {
    }

    private final CommunityAnnouncementRepository announcementRepository;
    private final CommunityPostRepository postRepository;
    private final CommunityTopicRepository topicRepository;
    private final CommunitySettingsService settingsService;
    private final AuditLogService auditLogService;

    public CommunityAnnouncementService(CommunityAnnouncementRepository announcementRepository,
                                        CommunityPostRepository postRepository,
                                        CommunityTopicRepository topicRepository,
                                        CommunitySettingsService settingsService,
                                        AuditLogService auditLogService) {
        this.announcementRepository = announcementRepository;
        this.postRepository = postRepository;
        this.topicRepository = topicRepository;
        this.settingsService = settingsService;
        this.auditLogService = auditLogService;
    }

    public List<AnnouncementView> list() {
        Instant now = Instant.now();
        List<CommunityAnnouncement> all = announcementRepository.findAllByOrderByCreatedAtDesc();
        Map<UUID, CommunityPost> posts = new HashMap<>();
        postRepository.findAllById(all.stream().map(CommunityAnnouncement::getPostId).toList()).forEach(p -> posts.put(p.getId(), p));
        return all.stream().filter(a -> posts.containsKey(a.getPostId()))
                .map(a -> {
                    CommunityPost p = posts.get(a.getPostId());
                    boolean live = a.isLive(now) && p.getStatus() == CommunityContentStatus.ACTIVE && p.getDeletedAt() == null;
                    return new AnnouncementView(a, p, live);
                }).toList();
    }

    public Optional<Banner> activeBanner() {
        Instant now = Instant.now();
        for (CommunityAnnouncement a : announcementRepository.findByShowBannerTrueOrderByCreatedAtDesc()) {
            if (!a.isLive(now)) {
                continue;
            }
            Optional<CommunityPost> post = postRepository.findByIdAndDeletedAtIsNull(a.getPostId())
                    .filter(p -> p.getStatus() == CommunityContentStatus.ACTIVE);
            if (post.isPresent()) {
                String text = a.getBannerText() != null && !a.getBannerText().isBlank() ? a.getBannerText()
                        : Objects.requireNonNullElse(post.get().getTitle(), "New announcement from the Jachai Team");
                return Optional.of(new Banner(a.getId(), a.getPostId(), text, a.getTargetScope().name(),
                        a.getTargetAreaId(), a.getTargetTopic()));
            }
        }
        return Optional.empty();
    }

    @Transactional
    public AnnouncementView create(AnnouncementInput in) {
        CommunityModerationService.requireAdmin();
        validate(in);
        String topic = resolveTopic(in);
        CommunityPost post = postRepository.save(CommunityPost.builder()
                .authorUserId(CurrentUser.id())
                .title(in.title().trim())
                .body(in.body().trim())
                .postType(CommunityPostType.DISCUSSION)
                .topic(topic)
                .areaId(in.scope() == CommunityAnnouncement.Scope.AREA ? in.areaId() : null)
                .official(true)
                .pinned(true)
                .pinScope(pinScope(in.scope()))
                .visibleFrom(in.startsAt())
                .visibleUntil(in.endsAt())
                .build());
        CommunityAnnouncement a = announcementRepository.save(CommunityAnnouncement.builder()
                .postId(post.getId())
                .targetScope(in.scope())
                .targetAreaId(in.scope() == CommunityAnnouncement.Scope.AREA ? in.areaId() : null)
                .targetTopic(in.scope() == CommunityAnnouncement.Scope.TOPIC ? topic : null)
                .startsAt(in.startsAt())
                .endsAt(in.endsAt())
                .showBanner(in.showBanner())
                .bannerText(blankToNull(in.bannerText()))
                .createdBy(CurrentUser.id())
                .build());
        auditLogService.record("COMMUNITY_ANNOUNCEMENT", a.getId(), "ANNOUNCEMENT_CREATED", in.title(), null, snapshot(a, post));
        settingsService.evict();
        return new AnnouncementView(a, post, a.isLive(Instant.now()));
    }

    @Transactional
    public AnnouncementView update(UUID announcementId, AnnouncementInput in) {
        CommunityModerationService.requireAdmin();
        validate(in);
        CommunityAnnouncement a = require(announcementId);
        CommunityPost post = postRepository.findById(a.getPostId())
                .orElseThrow(() -> new ResourceNotFoundException("Announcement post not found"));
        Map<String, Object> before = snapshot(a, post);
        String topic = resolveTopic(in);
        post.setTitle(in.title().trim());
        post.setBody(in.body().trim());
        post.setTopic(topic);
        post.setAreaId(in.scope() == CommunityAnnouncement.Scope.AREA ? in.areaId() : null);
        post.setPinned(true);
        post.setPinScope(pinScope(in.scope()));
        post.setVisibleFrom(in.startsAt());
        post.setVisibleUntil(in.endsAt());
        postRepository.save(post);
        a.setTargetScope(in.scope());
        a.setTargetAreaId(in.scope() == CommunityAnnouncement.Scope.AREA ? in.areaId() : null);
        a.setTargetTopic(in.scope() == CommunityAnnouncement.Scope.TOPIC ? topic : null);
        a.setStartsAt(in.startsAt());
        a.setEndsAt(in.endsAt());
        a.setShowBanner(in.showBanner());
        a.setBannerText(blankToNull(in.bannerText()));
        announcementRepository.save(a);
        auditLogService.record("COMMUNITY_ANNOUNCEMENT", a.getId(), "ANNOUNCEMENT_UPDATED", in.title(), before, snapshot(a, post));
        settingsService.evict();
        return new AnnouncementView(a, post, a.isLive(Instant.now()));
    }

    /** Ends it now — the post leaves every feed and the banner disappears; the row stays for history. */
    @Transactional
    public void end(UUID announcementId, String reason) {
        CommunityModerationService.requireAdmin();
        CommunityAnnouncement a = require(announcementId);
        CommunityPost post = postRepository.findById(a.getPostId())
                .orElseThrow(() -> new ResourceNotFoundException("Announcement post not found"));
        Map<String, Object> before = snapshot(a, post);
        Instant now = Instant.now();
        a.setEndsAt(now);
        announcementRepository.save(a);
        post.setVisibleUntil(now);
        post.setPinned(false);
        postRepository.save(post);
        auditLogService.record("COMMUNITY_ANNOUNCEMENT", a.getId(), "ANNOUNCEMENT_ENDED", reason, before, snapshot(a, post));
        settingsService.evict();
    }

    public CommunityAnnouncement require(UUID id) {
        return announcementRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Announcement not found"));
    }

    private void validate(AnnouncementInput in) {
        if (in.title() == null || in.title().isBlank() || in.title().trim().length() > 150) {
            throw new BadRequestException("Title is required (max 150 characters)");
        }
        if (in.body() == null || in.body().isBlank() || in.body().length() > 20000) {
            throw new BadRequestException("Announcement text is required");
        }
        if (in.scope() == null) {
            throw new BadRequestException("Choose who should see the announcement");
        }
        if (in.scope() == CommunityAnnouncement.Scope.AREA && in.areaId() == null) {
            throw new BadRequestException("Pick the area to target");
        }
        if (in.scope() == CommunityAnnouncement.Scope.TOPIC && (in.topic() == null || in.topic().isBlank())) {
            throw new BadRequestException("Pick the topic to target");
        }
        if (in.startsAt() != null && in.endsAt() != null && !in.endsAt().isAfter(in.startsAt())) {
            throw new BadRequestException("The end must be after the start");
        }
        if (in.bannerText() != null && in.bannerText().length() > 280) {
            throw new BadRequestException("Banner text is too long (max 280 characters)");
        }
    }

    private String resolveTopic(AnnouncementInput in) {
        if (in.scope() == CommunityAnnouncement.Scope.TOPIC) {
            String code = in.topic().trim().toUpperCase(Locale.ROOT);
            if (!topicRepository.existsById(code)) {
                throw new BadRequestException("Unknown topic");
            }
            return code;
        }
        return settingsService.config().defaultTopicCode();
    }

    private static String pinScope(CommunityAnnouncement.Scope scope) {
        return switch (scope) {
            case ALL -> "GLOBAL";
            case TOPIC -> "TOPIC";
            case AREA -> "AREA";
        };
    }

    private static Map<String, Object> snapshot(CommunityAnnouncement a, CommunityPost p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("postId", p.getId());
        m.put("title", p.getTitle());
        m.put("scope", a.getTargetScope().name());
        m.put("areaId", a.getTargetAreaId());
        m.put("topic", a.getTargetTopic());
        m.put("startsAt", a.getStartsAt());
        m.put("endsAt", a.getEndsAt());
        m.put("showBanner", a.isShowBanner());
        m.put("bannerText", a.getBannerText());
        return m;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}

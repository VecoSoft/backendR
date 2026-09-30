package com.bdreview.platform.community.settings;

import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.community.moderation.CommunityAnnouncementService;
import com.bdreview.platform.community.moderation.CommunityRestriction;
import com.bdreview.platform.community.moderation.CommunityRestrictionRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public, read-only community configuration + the caller's own standing. Both stay reachable
 * while the community is in maintenance (the app needs them to render the maintenance state).
 */
@RestController
@RequestMapping("/api/v1/community")
public class CommunitySettingsController {

    private final CommunitySettingsService settingsService;
    private final FeatureFlagService featureFlagService;
    private final CommunityAnnouncementService announcementService;
    private final CommunityRestrictionRepository restrictionRepository;

    public CommunitySettingsController(CommunitySettingsService settingsService,
                                       FeatureFlagService featureFlagService,
                                       CommunityAnnouncementService announcementService,
                                       CommunityRestrictionRepository restrictionRepository) {
        this.settingsService = settingsService;
        this.featureFlagService = featureFlagService;
        this.announcementService = announcementService;
        this.restrictionRepository = restrictionRepository;
    }

    @GetMapping("/settings")
    public ResponseEntity<PublicCommunitySettings> settings() {
        CommunityConfig cfg = settingsService.config();
        CommunitySettings s = cfg.settings();
        Map<String, Boolean> postTypes = new LinkedHashMap<>();
        postTypes.put("DISCUSSION", s.getPostTypes().isDiscussionEnabled());
        postTypes.put("QUESTION", s.getPostTypes().isQuestionEnabled());
        postTypes.put("RECOMMENDATION", s.getPostTypes().isRecommendationEnabled());
        postTypes.put("POLL", s.getPostTypes().isPollEnabled());
        var c = s.getContent();
        PublicCommunitySettings body = new PublicCommunitySettings(
                s.getGeneral().isCommunityEnabled(),
                s.getGeneral().getMaintenanceMessage(),
                s.getGeneral().isReadOnly(),
                s.getGeneral().getReadOnlyMessage(),
                s.getGeneral().isGuestsCanRead(),
                cfg.topics(),
                cfg.defaultTopicCode(),
                postTypes,
                s.getPostTypes().isImagesEnabled(),
                s.getPostTypes().getMaxImagesPerPost(),
                s.getPostTypes().getMaxImageSizeMb(),
                s.getPostTypes().isPollEnabled(),
                new PublicCommunitySettings.Limits(c.getPostBodyMin(), c.getPostBodyMax(), c.getQuestionTitleMin(),
                        c.getQuestionTitleMax(), c.getCommentMin(), c.getCommentMax(), c.getMaxLinksPerPost()),
                s.getAreas().getAllowedAreaIds(),
                s.getRulesMarkdown(),
                new PublicCommunitySettings.Features(featureFlagService.nidVerificationEnabled()),
                announcementService.activeBanner().orElse(null));
        // Short browser cache only — admin changes must show up within seconds.
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(body);
    }

    /** The caller's community standing: active restrictions (mute/suspend/ban) and recent warnings. Empty for guests. */
    @GetMapping("/me/standing")
    public ResponseEntity<Standing> standing() {
        UUID userId = CurrentUser.idOrNull();
        if (userId == null) {
            return ResponseEntity.ok(new Standing(false, null, List.of()));
        }
        Instant now = Instant.now();
        List<StandingItem> items = restrictionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(r -> r.isInEffect(now)
                        || (r.getType() == CommunityRestriction.Type.WARN && r.getStatus() == CommunityRestriction.Status.ACTIVE
                        && r.getCreatedAt().isAfter(now.minusSeconds(30L * 24 * 3600))))
                .map(r -> new StandingItem(r.getId(), r.getType().name(), r.getReason(), r.getStartsAt(), r.getEndsAt()))
                .toList();
        StandingItem blocking = items.stream().filter(i -> !"WARN".equals(i.type())).findFirst().orElse(null);
        return ResponseEntity.ok(new Standing(blocking != null, blocking, items));
    }

    public record StandingItem(UUID id, String type, String reason, Instant startsAt, Instant endsAt) {
    }

    /** restricted = can't post/comment/vote right now; `restriction` is the one in force. */
    public record Standing(boolean restricted, StandingItem restriction, List<StandingItem> items) {
    }
}

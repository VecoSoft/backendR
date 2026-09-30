package com.bdreview.platform.community.settings;

import com.bdreview.platform.community.moderation.CommunityAnnouncementService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only subset of the community settings the Next.js app needs to mirror the server rules
 * (GET /api/v1/community/settings, public). Nothing sensitive: no banned-word list, no rate
 * limits per tier, no moderation thresholds.
 */
public record PublicCommunitySettings(
        boolean communityEnabled,
        String maintenanceMessage,
        boolean readOnly,
        String readOnlyMessage,
        boolean guestsCanRead,
        /** Every topic (disabled ones too, so old posts still render their badge) — `enabled` says which can be picked. */
        List<TopicView> topics,
        String defaultTopic,
        /** DISCUSSION / QUESTION / RECOMMENDATION / POLL → enabled. */
        Map<String, Boolean> postTypes,
        boolean imagesEnabled,
        int maxImagesPerPost,
        int maxImageSizeMb,
        boolean pollsEnabled,
        Limits limits,
        /** Areas a post may be tagged with; empty = all. */
        List<UUID> allowedAreaIds,
        String rulesMarkdown,
        Features features,
        /** Active "Jachai Team" banner, or null. */
        CommunityAnnouncementService.Banner banner
) {

    public record Limits(int postBodyMin, int postBodyMax, int questionTitleMin, int questionTitleMax,
                         int commentMin, int commentMax, int maxLinksPerPost) {
    }

    public record Features(boolean nidVerificationEnabled) {
    }
}

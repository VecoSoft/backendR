package com.bdreview.platform.community.moderation;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Targeting/scheduling/banner metadata for a "Jachai Team" announcement (V56). The announcement
 * itself is an ordinary community_post with {@code official = true} (pinned, shown with the
 * official badge), so it reuses the whole feed/detail/comment machinery.
 */
@Entity
@Table(name = "community_announcement")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityAnnouncement {

    public enum Scope { ALL, AREA, TOPIC }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_scope", nullable = false, length = 10)
    private Scope targetScope;

    @Column(name = "target_area_id")
    private UUID targetAreaId;

    @Column(name = "target_topic", length = 20)
    private String targetTopic;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Builder.Default
    @Column(name = "show_banner", nullable = false)
    private boolean showBanner = false;

    @Column(name = "banner_text", length = 280)
    private String bannerText;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    @Transient
    public boolean isLive(Instant now) {
        return (startsAt == null || !startsAt.isAfter(now)) && (endsAt == null || endsAt.isAfter(now));
    }
}

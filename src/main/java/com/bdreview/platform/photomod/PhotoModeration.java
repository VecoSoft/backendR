package com.bdreview.platform.photomod;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One photo in the moderation queue — see V63 and {@link PhotoModerationService}. */
@Entity
@Table(name = "photo_moderation")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PhotoModeration {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private PhotoSource sourceType;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "business_id")
    private UUID businessId;

    @Column(nullable = false, columnDefinition = "text")
    private String url;

    @Column(name = "object_key", columnDefinition = "text")
    private String objectKey;

    @Column(name = "uploader_user_id")
    private UUID uploaderUserId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private PhotoStatus status = PhotoStatus.PENDING;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}

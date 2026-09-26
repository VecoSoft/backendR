package com.bdreview.platform.catalog;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A quick-question shortcut for the chat widget — tapping it in the customer-facing
 * chat sends the question and auto-posts {@code answer} as an owner-authored reply
 * (see MessageService#triggerAutoReply). Business-wide, not category-scoped, same
 * as Faq, which this entity deliberately mirrors.
 */
@Entity
@Table(name = "business_auto_reply")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessAutoReply {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false, length = 300)
    private String question;

    @Column(nullable = false, columnDefinition = "text")
    private String answer;

    /** "en" / "bn" for a system-seeded default (so the widget can show only the visitor's
     *  site-language variant), or null for an owner-authored entry, shown regardless of language. */
    @Column(length = 10)
    private String language;

    /** Informational only — owners can freely edit/delete these too, same as any other row.
     *  Lets the owner's management UI show a "Default" badge. */
    @Builder.Default
    @Column(name = "system_default", nullable = false)
    private boolean systemDefault = false;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}

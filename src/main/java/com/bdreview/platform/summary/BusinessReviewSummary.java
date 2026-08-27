// BusinessReviewSummary.java
package com.bdreview.platform.summary;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Spec §15. One cached row per business — regenerated only when 10 new reviews
 * have landed since `reviewCountAtGeneration`, never on every profile view
 * (LLM cost control). `version` backs the optimistic-lock / SELECT ... FOR UPDATE
 * guard so a burst of qualifying reviews triggers at most one regeneration job.
 */
@Entity
@Table(name = "business_review_summary")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessReviewSummary {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false, unique = true)
    private UUID businessId;

    @Column(name = "summary_text", nullable = false, columnDefinition = "text")
    private String summaryText;

    @Column(name = "review_count_at_generation", nullable = false)
    private int reviewCountAtGeneration;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Version
    private long version;
}
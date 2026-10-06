package com.bdreview.platform.adminconfig;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Moderation → Review settings (V65), read by the review pipeline at runtime. */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewPolicyConfig {
    /** Minimum review text length (trimmed characters). */
    private int minLength = 10;
    /** Hours after submission during which the author may edit/delete (spec §4 used a fixed 72). */
    private int editWindowHours = 72;
    /** Max new reviews one account may post in a rolling 24 hours. */
    private int maxReviewsPerUserPerDay = 5;
    /** ML suspicion score at or above which a review is auto NOT_RECOMMENDED (0-100). */
    private int notRecommendedThreshold = 31;
    /** ML suspicion score at or above which a review is auto HIDDEN (0-100). */
    private int hiddenThreshold = 71;
    /** Block reviews of a business that competes (same category + area) with one the reviewer owns. */
    private boolean competitorRuleEnabled = true;
}

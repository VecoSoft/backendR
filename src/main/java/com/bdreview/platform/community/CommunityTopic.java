package com.bdreview.platform.community;

/**
 * Community discussion topic — deliberately broader than business.CategoryKind
 * (which only covers the six business-listing categories); topics like LOCAL/
 * JOBS/EDUCATION/TRAVEL/GENERAL have no business-category equivalent. String-
 * CHECK-backed in the DB (see V33), so more topics can be added later by a
 * small migration, same convention as notification.NotificationType.
 */
public enum CommunityTopic {
    FOOD, HEALTHCARE, BEAUTY, SHOPPING, FITNESS, LOCAL,
    SERVICES, JOBS, EDUCATION, TRAVEL, GENERAL
}

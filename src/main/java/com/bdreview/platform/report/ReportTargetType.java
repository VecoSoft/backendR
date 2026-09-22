package com.bdreview.platform.report;

public enum ReportTargetType {
    REVIEW, LISTING,
    /** "Join Community" — see report.ReportService#resolve and V33's widened CHECK constraint. */
    COMMUNITY_POST, COMMUNITY_COMMENT,
    /** Offers — see report.ReportService#resolve and V36's widened CHECK constraint. */
    OFFER
}

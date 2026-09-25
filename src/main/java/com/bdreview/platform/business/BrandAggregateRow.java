package com.bdreview.platform.business;

import java.util.UUID;

/** Spring Data native-query projection for {@link BrandRepository#aggregatesFor}. */
public interface BrandAggregateRow {
    UUID getBrandId();
    long getBranchCount();
    long getRatingSum();
    long getReviewCount();
}

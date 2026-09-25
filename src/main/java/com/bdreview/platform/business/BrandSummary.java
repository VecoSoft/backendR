package com.bdreview.platform.business;

import java.math.BigDecimal;
import java.util.UUID;

/** Threaded into BusinessResponse#from to populate brandId/brandName/brandSlug/branchCount/brandAverageRating. */
public record BrandSummary(UUID id, String name, String slug, int branchCount, BigDecimal averageRating) {
}

package com.bdreview.platform.completeness;

import java.util.List;

/**
 * Profile completeness for the owner dashboard (Phase 3). Computed on the fly
 * from existing data — no stored percentage. Equal weight per applicable item,
 * so {@code percentage = round(completed / applicable * 100)}. Items irrelevant
 * to the listing's category kind are never in the list, so a GENERAL business is
 * not marked down for having no menu.
 */
public record CompletenessResponse(
        int percentage,
        List<Item> completed,
        List<Item> recommended
) {
    /** {@code action} is a short call-to-action; null for already-completed items. */
    public record Item(String key, String label, String action) {
    }
}

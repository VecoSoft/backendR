package com.bdreview.platform.review;

import java.util.function.IntSupplier;

/**
 * Static bridge for the one review rule an entity method needs: the edit/delete window
 * ({@link Review#isWithinEditWindow()}, which every ReviewResponse uses for its "editable" flag).
 * The admin-configured value (Moderation → Review settings, V65) is plugged in at startup by
 * {@code adminconfig.ReviewPolicyBridge}; until then — and in plain unit tests — it is 72 hours.
 */
public final class ReviewPolicyHolder {

    public static final int DEFAULT_EDIT_WINDOW_HOURS = 72;

    private static volatile IntSupplier editWindowHours = () -> DEFAULT_EDIT_WINDOW_HOURS;

    private ReviewPolicyHolder() {
    }

    public static int editWindowHours() {
        try {
            return editWindowHours.getAsInt();
        } catch (RuntimeException e) {
            return DEFAULT_EDIT_WINDOW_HOURS;
        }
    }

    public static void setEditWindowSource(IntSupplier source) {
        editWindowHours = source == null ? () -> DEFAULT_EDIT_WINDOW_HOURS : source;
    }
}

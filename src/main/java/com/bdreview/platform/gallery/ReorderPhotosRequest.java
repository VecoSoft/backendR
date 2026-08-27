package com.bdreview.platform.gallery;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * Full new display order for a business's gallery photos: every existing photo
 * id, exactly once, in the order the owner wants them shown. {@code sort_order}
 * is then rewritten to match the list index.
 */
public record ReorderPhotosRequest(@NotEmpty List<UUID> orderedPhotoIds) {
}

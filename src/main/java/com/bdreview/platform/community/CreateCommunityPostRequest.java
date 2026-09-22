package com.bdreview.platform.community;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The composer is a single text box (no separate title field in the UI), so
 * body is the required content and title is optional (nullable — kept in
 * the model for a future/alternate entry point, never required by the
 * current frontend). imageUrls is an optional set of photo attachments (up
 * to CommunityPostService#MAX_POST_PHOTOS) — CDN URLs returned by
 * CommunityPostController#requestUploadUrl after a direct-to-storage
 * upload, same pre-signed-URL flow as the business gallery (see
 * gallery.ConfirmUploadRequest); never raw image bytes. Stored one row per
 * photo in community_post_photo (see V43), ordered as given.
 * businessId is a single optional reference, resolved live when rendering —
 * never duplicated into the post row. areaId is optional and powers
 * "Nearby"; if omitted but businessId is present, CommunityPostService
 * infers it from the business's own area.
 * pollOptions/pollDurationHours are only read when postType == POLL (the
 * post's own body doubles as the poll question); validated in
 * CommunityPostService#validatePollRequest, not here, so the error messages
 * can be specific (2-6 options, one of the 3 allowed durations).
 */
public record CreateCommunityPostRequest(
        @Size(max = 150) String title,
        @NotBlank @Size(max = 5000) String body,
        @NotNull CommunityPostType postType,
        @NotNull CommunityTopic topic,
        UUID businessId,
        UUID areaId,
        List<String> pollOptions,
        Integer pollDurationHours,
        List<@Size(max = 2048) String> imageUrls
) {
}

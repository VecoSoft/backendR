package com.bdreview.platform.updates;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Tiny request bodies for the Phase 3 updates endpoints. */
public final class BusinessUpdateRequests {

    private BusinessUpdateRequests() {
    }

    public record UpsertRequest(
            @NotBlank @Size(max = 2000) String body,
            String imageUrl,
            /** null => keep current on edit, default true on create. */
            Boolean published
    ) {
    }

    public record PublishRequest(boolean published) {
    }
}

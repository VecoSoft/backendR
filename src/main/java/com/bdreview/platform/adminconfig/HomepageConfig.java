package com.bdreview.platform.adminconfig;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** System → Homepage curation (V65). Blank/empty values fall back to the app's built-in homepage. */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class HomepageConfig {

    public static final int MAX_PINS = 3;

    private String heroTitle;
    private String heroSubtitle;
    /** Live hero image — only ever an APPROVED photo (a new upload waits in Moderation → Photos). */
    private String heroImageUrl;
    /** Ordered category ids shown as "featured categories". */
    private List<UUID> featuredCategoryIds = new ArrayList<>();
    private Section trending = new Section();
    private Section mostLoved = new Section();

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Section {
        /** A business needs at least this many counted reviews to appear (pins are exempt). */
        private int minReviewCount = 0;
        /** Never shown in this section. */
        private List<UUID> excludedBusinessIds = new ArrayList<>();
        /** Shown first, in order, until {@link Pin#getEndsAt()} (max {@value HomepageConfig#MAX_PINS}). */
        private List<Pin> pins = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pin {
        private UUID businessId;
        private Instant endsAt;

        public boolean isActive(Instant now) {
            return businessId != null && (endsAt == null || endsAt.isAfter(now));
        }
    }
}

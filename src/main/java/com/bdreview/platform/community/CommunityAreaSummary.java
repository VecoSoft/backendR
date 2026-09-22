package com.bdreview.platform.community;

import java.util.UUID;

/** Lightweight area label for a post's optional location tag (powers "Nearby" — e.g. "Dhanmondi"). */
public record CommunityAreaSummary(UUID id, String name, String cityName) {
}

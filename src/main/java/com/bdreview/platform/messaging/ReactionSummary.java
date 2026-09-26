package com.bdreview.platform.messaging;

/** One emoji's aggregate on a message — e.g. "👍" x3, and whether the viewer is one of the 3. */
public record ReactionSummary(String emoji, int count, boolean reactedByMe) {
}

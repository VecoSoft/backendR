package com.bdreview.platform.promo;

import java.util.UUID;

/**
 * Attached to every paid placement. The client must render a "Sponsored" label whenever this is
 * present. {@code why} is the plain "Why am I seeing this?" answer — targeting only (area or
 * distance), never anything about the viewer's behaviour.
 */
public record SponsoredInfo(UUID boostId, String why) {
}

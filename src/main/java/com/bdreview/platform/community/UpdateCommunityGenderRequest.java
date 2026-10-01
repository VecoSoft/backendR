package com.bdreview.platform.community;

/** V59: both optional — send {@code gender} ("M"/"F") to pick/change it, {@code visible} to show or hide the badge. */
public record UpdateCommunityGenderRequest(String gender, Boolean visible) {
}

package com.bdreview.platform.community;

import java.util.UUID;

/**
 * V58: the public identity a business post/comment is shown under — the business itself, never
 * the owner's pseudonymous community account (so a business post can't be used to unmask who
 * runs a u/username).
 */
public record BusinessIdentity(UUID id, String name, String slug, String logoUrl, boolean verified, String areaName) {
}

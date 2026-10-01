package com.bdreview.platform.promo;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Owner-uploaded promo images (V61) — a banner designed elsewhere, or a photo for a template.
 * Stored only under {@code promo/<businessId>/uploads/<uuid>.jpg} (the Studio converts every
 * upload to JPEG in the browser first), so a URL can be traced back to the business it belongs
 * to and one business can never put another's upload on its creative.
 */
final class PromoUploads {

    static final String CUSTOM_TEMPLATE = "CUSTOM";
    private static final Pattern FILE = Pattern.compile("^[0-9a-f-]{36}\\.jpg$");

    private PromoUploads() {
    }

    static String keyPrefix(UUID businessId) {
        return "promo/" + businessId + "/uploads/";
    }

    static String newKey(UUID businessId) {
        return keyPrefix(businessId) + UUID.randomUUID() + ".jpg";
    }

    /** True when {@code url} is exactly a CDN URL of one of this business's uploads. */
    static boolean isOwnUpload(String url, UUID businessId, String cdnPrefix) {
        if (url == null || cdnPrefix == null || !url.startsWith(cdnPrefix) || url.contains("..")) {
            return false;
        }
        return FILE.matcher(url.substring(cdnPrefix.length())).matches();
    }

    /** Cheap check on a stored creative's JSON: does it use one of this business's uploads? */
    static boolean mentionsOwnUpload(String dataJson, UUID businessId) {
        return dataJson != null && dataJson.contains(keyPrefix(businessId));
    }
}

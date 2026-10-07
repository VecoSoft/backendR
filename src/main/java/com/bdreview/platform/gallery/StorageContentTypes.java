package com.bdreview.platform.gallery;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Content types for stored objects, decided by file extension only, never by what the uploader
 * claims. Only images and PDFs are ever served inline; everything else is served as an
 * octet-stream attachment, so an uploaded .html or .svg can't run script on the API's origin
 * (which also hosts the admin panel).
 */
public final class StorageContentTypes {

    private static final Map<String, String> INLINE = Map.of(
            "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "webp", "image/webp",
            "gif", "image/gif", "avif", "image/avif", "pdf", "application/pdf");

    /** Raster formats the variant generator can decode. */
    public static final Set<String> RESIZABLE = Set.of("jpg", "jpeg", "png", "webp");

    private StorageContentTypes() {
    }

    public static String extension(String key) {
        int slash = key.lastIndexOf('/');
        int dot = key.lastIndexOf('.');
        return dot > slash ? key.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static String forKey(String key) {
        return INLINE.getOrDefault(extension(key), "application/octet-stream");
    }

    public static boolean isInline(String contentType) {
        return INLINE.containsValue(contentType);
    }
}

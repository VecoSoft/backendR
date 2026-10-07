package com.bdreview.platform.gallery;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.FeatureDisabledException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.common.SharedCache;
import com.bdreview.platform.photomod.PhotoModerationService;
import com.bdreview.platform.features.FeatureFlagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;

/**
 * The upload/download endpoint behind every storage URL the API hands out (spec §13).
 *
 * <ul>
 *   <li>PUT {@code /upload/{key}?exp=&sig=}: only with a signature the API issued for that key
 *       ({@link StorageUrlSigner}); then queues the WebP variants.</li>
 *   <li>GET {@code /files/{key}[?w=600]}: access rules first (NID gate, photo moderation, ADMIN-only
 *       documents at the security layer), then either the bytes or, in S3 redirect mode, a 302 to a
 *       presigned bucket URL valid for {@code app.storage.presigned-get-ttl-seconds}. A pending,
 *       rejected or deleted photo is therefore never reachable without passing the check.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/storage")
public class StorageController {

    /** Hard cap on one upload; matches Caddy's request_body limit. Per-type limits apply where the upload is requested. */
    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    private final ObjectStorageClient storage;
    private final StorageUrlSigner urls;
    private final ImageVariantService variants;
    private final SharedCache cache;
    private final FeatureFlagService featureFlags;
    private final PhotoModerationService photoModeration;
    private final boolean redirect;
    private final Duration presignTtl;

    public StorageController(ObjectStorageClient storage, StorageUrlSigner urls, ImageVariantService variants,
                             SharedCache cache, FeatureFlagService featureFlags, PhotoModerationService photoModeration,
                             @Value("${app.storage.serve}") String serve,
                             @Value("${app.storage.presigned-get-ttl-seconds}") long presignTtlSeconds) {
        this.storage = storage;
        this.urls = urls;
        this.variants = variants;
        this.cache = cache;
        this.featureFlags = featureFlags;
        this.photoModeration = photoModeration;
        this.redirect = "redirect".equalsIgnoreCase(serve);
        this.presignTtl = Duration.ofSeconds(Math.max(60, presignTtlSeconds));
    }

    @PutMapping("/upload/{*key}")
    public ResponseEntity<Void> upload(@PathVariable String key, @RequestParam(required = false) Long exp,
                                       @RequestParam(required = false) String sig,
                                       jakarta.servlet.http.HttpServletRequest request) throws IOException {
        String cleaned = cleaned(key);
        // NID verification is feature-flagged (off by default): no NID files are accepted while it's off.
        if (isNidKey(cleaned) && !featureFlags.nidVerificationEnabled()) {
            throw new FeatureDisabledException();
        }
        if (isVariantKey(cleaned) || !urls.verifyUpload(cleaned, exp, sig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        byte[] content;
        try (InputStream in = request.getInputStream()) {
            content = in.readNBytes(MAX_UPLOAD_BYTES + 1);
        }
        if (content.length > MAX_UPLOAD_BYTES) {
            throw new BadRequestException("File is too large (max 10 MB)");
        }
        storage.putObject(cleaned, content);
        variants.generateLater(cleaned, content);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/files/{*key}")
    public ResponseEntity<byte[]> get(@PathVariable String key, @RequestParam(name = "w", required = false) Integer width) {
        String cleaned = cleaned(key);
        if (isVariantKey(cleaned)) {
            throw new ResourceNotFoundException("File not found"); // variants only via ?w= on the original
        }
        // Reads under nid/ are ADMIN-only at the security layer; while the feature is off they 404 for everyone.
        if (isNidKey(cleaned) && !featureFlags.nidVerificationEnabled()) {
            throw new FeatureDisabledException();
        }
        // V63: a pending photo is visible only to its uploader (and admins); a rejected or
        // admin-deleted one to nobody — the file is kept for the audit trail. 404, not 403, so the
        // response doesn't confirm the object exists.
        if (!photoModeration.canServe(cleaned, CurrentUser.idOrNull(), CurrentUser.hasRole("ADMIN"))) {
            throw new ResourceNotFoundException("File not found");
        }
        String target = pickVariant(cleaned, width);
        String contentType = target.equals(cleaned) ? StorageContentTypes.forKey(cleaned) : "image/webp";
        if (redirect) {
            var presigned = storage.presignedGetUrl(target, presignTtl);
            if (presigned.isPresent()) {
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(presigned.get())
                        // Never cache longer than the presigned URL lives; private: per-viewer decision.
                        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=" + (presignTtl.toSeconds() - 30))
                        .build();
            }
        }
        byte[] bytes = storage.getObject(target);
        boolean inline = StorageContentTypes.isInline(contentType);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox")
                .header(HttpHeaders.CONTENT_DISPOSITION, inline ? "inline" : "attachment")
                .body(bytes);
    }

    /** The variant key to serve for {@code ?w=}, or the original when there is none (yet). */
    private String pickVariant(String key, Integer requested) {
        Integer w = variants.pickWidth(requested);
        if (w == null || !variants.wantsVariants(key)) {
            return key;
        }
        String vKey = ImageVariantService.variantKey(key, w);
        // Existence is cached (shared across instances) so a ?w= request doesn't cost a HEAD every time.
        // "Missing" is cached briefly only: variants are written a few seconds after the upload.
        String cacheKey = ImageVariantService.existsCacheKey(vKey);
        String exists = cache.peek(cacheKey).orElseGet(() -> {
            String found = storage.size(vKey).isPresent() ? "1" : "0";
            cache.put(cacheKey, found, "1".equals(found) ? Duration.ofHours(6) : Duration.ofMinutes(1));
            return found;
        });
        return "1".equals(exists) ? vKey : key;
    }

    /** Stored NID images live under "nid/" (from the pre-V13 NID flow — kept, never deleted). */
    private static boolean isNidKey(String cleaned) {
        return cleaned.toLowerCase(Locale.ROOT).startsWith("nid/");
    }

    private static boolean isVariantKey(String cleaned) {
        return cleaned.startsWith(StorageUrlSigner.VARIANT_PREFIX);
    }

    private static String cleaned(String key) {
        String cleaned = key.startsWith("/") ? key.substring(1) : key;
        if (cleaned.isBlank()) {
            throw new BadRequestException("Missing object key");
        }
        return cleaned;
    }
}

package com.bdreview.platform.gallery;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.FeatureDisabledException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.photomod.PhotoModerationService;
import com.bdreview.platform.features.FeatureFlagService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;

/**
 * The upload/download endpoint behind every storage URL the API hands out: accepts the raw PUT a
 * client sends to an upload URL and serves files back over GET, applying the access rules below.
 * Bytes go through {@link ObjectStorageClient}: local disk ({@link DevObjectStorageClient}) or a
 * private S3/Spaces bucket ({@link S3ObjectStorageClient}). See spec §13.
 */
@RestController
@RequestMapping("/api/v1/storage")
public class StorageController {

    /** Hard cap on one upload. Per-type limits (e.g. app.photo.max-size-mb) are enforced where the upload is requested. */
    private static final int MAX_UPLOAD_BYTES = 25 * 1024 * 1024;

    private final ObjectStorageClient storage;
    private final FeatureFlagService featureFlags;
    private final PhotoModerationService photoModeration;

    public StorageController(ObjectStorageClient storage, FeatureFlagService featureFlags,
                             PhotoModerationService photoModeration) {
        this.storage = storage;
        this.featureFlags = featureFlags;
        this.photoModeration = photoModeration;
    }

    @PutMapping("/upload/{*key}")
    public ResponseEntity<Void> upload(@PathVariable String key, jakarta.servlet.http.HttpServletRequest request)
            throws IOException {
        // NID verification is feature-flagged (off by default): no NID files are accepted while it's off.
        if (isNidKey(key) && !featureFlags.nidVerificationEnabled()) {
            throw new FeatureDisabledException();
        }
        byte[] content;
        try (InputStream in = request.getInputStream()) {
            content = in.readNBytes(MAX_UPLOAD_BYTES + 1);
        }
        if (content.length > MAX_UPLOAD_BYTES) {
            throw new BadRequestException("File is too large");
        }
        storage.putObject(cleaned(key), content);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/files/{*key}")
    public ResponseEntity<byte[]> get(@PathVariable String key) {
        // Reads under nid/ are ADMIN-only at the security layer; while the feature is off they 404 for everyone.
        if (isNidKey(key) && !featureFlags.nidVerificationEnabled()) {
            throw new FeatureDisabledException();
        }
        // V63: a pending photo is visible only to its uploader (and admins); a rejected or
        // admin-deleted one to nobody — the file is kept for the audit trail. 404, not 403, so the
        // response doesn't confirm the object exists.
        if (!photoModeration.canServe(key, CurrentUser.idOrNull(), CurrentUser.hasRole("ADMIN"))) {
            throw new ResourceNotFoundException("File not found");
        }
        String cleaned = cleaned(key);
        byte[] bytes = storage.getObject(cleaned);
        String contentType = URLConnection.guessContentTypeFromName(cleaned);
        return ResponseEntity.ok()
                .contentType(contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    /** Stored NID images live under "nid/" (from the pre-V13 NID flow — kept, never deleted). */
    private static boolean isNidKey(String key) {
        return cleaned(key).toLowerCase(java.util.Locale.ROOT).startsWith("nid/");
    }

    private static String cleaned(String key) {
        String cleaned = key.startsWith("/") ? key.substring(1) : key;
        if (cleaned.isBlank()) {
            throw new BadRequestException("Missing object key");
        }
        return cleaned;
    }
}

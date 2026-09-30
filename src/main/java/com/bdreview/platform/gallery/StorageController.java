package com.bdreview.platform.gallery;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.FeatureDisabledException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.community.settings.FeatureFlagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Backs {@link DevObjectStorageClient}: accepts the raw PUT a client sends to
 * a "pre-signed" upload URL and writes it to local disk, then serves it back
 * over GET. Local-dev/test stand-in for a real S3/R2 bucket — see spec §13.
 */
@RestController
@RequestMapping("/api/v1/storage")
@Profile("!prod")
public class StorageController {

    private final Path root;
    private final FeatureFlagService featureFlags;

    public StorageController(@Value("${app.storage.local-dir}") String localDir, FeatureFlagService featureFlags) {
        this.root = Path.of(localDir).toAbsolutePath().normalize();
        this.featureFlags = featureFlags;
    }

    @PutMapping("/upload/{*key}")
    public ResponseEntity<Void> upload(@PathVariable String key, jakarta.servlet.http.HttpServletRequest request)
            throws IOException {
        // NID verification is feature-flagged (off by default): no NID files are accepted while it's off.
        if (isNidKey(key) && !featureFlags.nidVerificationEnabled()) {
            throw new FeatureDisabledException();
        }
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        try (InputStream in = request.getInputStream()) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/files/{*key}")
    public ResponseEntity<Resource> get(@PathVariable String key) throws IOException {
        // Reads under nid/ are ADMIN-only at the security layer; while the feature is off they 404 for everyone.
        if (isNidKey(key) && !featureFlags.nidVerificationEnabled()) {
            throw new FeatureDisabledException();
        }
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) {
            throw new ResourceNotFoundException("File not found");
        }
        String contentType = URLConnection.guessContentTypeFromName(file.getFileName().toString());
        return ResponseEntity.ok()
                .contentType(contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    /** Stored NID images live under "nid/" (from the pre-V13 NID flow — kept, never deleted). */
    private static boolean isNidKey(String key) {
        String cleaned = key.startsWith("/") ? key.substring(1) : key;
        return cleaned.toLowerCase(java.util.Locale.ROOT).startsWith("nid/");
    }

    private Path resolve(String key) {
        String cleaned = key.startsWith("/") ? key.substring(1) : key;
        if (cleaned.isBlank()) {
            throw new BadRequestException("Missing object key");
        }
        Path resolved = root.resolve(cleaned).normalize();
        if (!resolved.startsWith(root)) {
            throw new BadRequestException("Invalid object key");
        }
        return resolved;
    }
}

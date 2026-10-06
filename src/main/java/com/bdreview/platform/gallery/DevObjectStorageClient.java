package com.bdreview.platform.gallery;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Local-disk object storage for the dev/test profile (spec §13). Bytes are
 * written to and served from {@code app.storage.local-dir} via
 * {@link StorageController} — a real deployment should implement
 * {@link ObjectStorageClient} against S3/R2 instead.
 */
@Component
@Profile("!prod")
public class DevObjectStorageClient implements ObjectStorageClient {

    private final String baseUrl;
    private final Path root;

    public DevObjectStorageClient(@Value("${app.storage.base-url}") String baseUrl,
                                   @Value("${app.storage.local-dir}") String localDir) {
        this.baseUrl = baseUrl;
        this.root = Path.of(localDir).toAbsolutePath().normalize();
    }

    @Override
    public String buildObjectKey(String folder, String ownerId, String filename) {
        // Folder convention from spec §13: business/{business_id}/..., review/{review_id}/..., claim-document/{user_id}/...
        return folder + "/" + ownerId + "/" + UUID.randomUUID() + "-" + filename;
    }

    @Override
    public String presignPutUrl(String objectKey) {
        return baseUrl + "/api/v1/storage/upload/" + objectKey;
    }

    @Override
    public String cdnUrlFor(String objectKey) {
        return baseUrl + "/api/v1/storage/files/" + objectKey;
    }

    @Override
    public String putObject(String objectKey, byte[] content) {
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new BadRequestException("Invalid object key");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return cdnUrlFor(objectKey);
    }

    @Override
    public byte[] getObject(String objectKey) {
        String cleaned = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        if (cleaned.isBlank()) {
            throw new BadRequestException("Missing object key");
        }
        Path resolved;
        try {
            resolved = root.resolve(cleaned).normalize();
        } catch (InvalidPathException e) {
            throw new ResourceNotFoundException("File not found");
        }
        if (!resolved.startsWith(root)) {
            throw new BadRequestException("Invalid object key");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new ResourceNotFoundException("File not found");
        }
        try {
            return Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

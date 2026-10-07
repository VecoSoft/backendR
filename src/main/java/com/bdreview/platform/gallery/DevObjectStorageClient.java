package com.bdreview.platform.gallery;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Local-disk object storage (spec §13) for development: {@code app.storage.mode=local}, the
 * default. Bytes are written to and read from {@code app.storage.local-dir} and served via
 * {@link StorageController}. Production uses {@link S3ObjectStorageClient}; ProductionEnvironmentCheck
 * refuses to start a production instance in local mode.
 */
@Component
@ConditionalOnProperty(name = "app.storage.mode", havingValue = "local", matchIfMissing = true)
public class DevObjectStorageClient implements ObjectStorageClient {

    private final StorageUrlSigner urls;
    private final Path root;

    public DevObjectStorageClient(StorageUrlSigner urls, @Value("${app.storage.local-dir}") String localDir) {
        this.urls = urls;
        this.root = Path.of(localDir).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    @Override
    public String buildObjectKey(String folder, String ownerId, String filename) {
        // Folder convention from spec §13: business/{business_id}/..., review/{review_id}/..., claim-document/{user_id}/...
        return folder + "/" + ownerId + "/" + UUID.randomUUID() + "-" + filename;
    }

    @Override
    public String presignPutUrl(String objectKey) {
        return urls.uploadUrl(objectKey);
    }

    @Override
    public String cdnUrlFor(String objectKey) {
        return urls.fileUrl(objectKey);
    }

    @Override
    public String putObject(String objectKey, byte[] content, String contentType) {
        Path target = resolve(objectKey);
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
        Path resolved = resolve(objectKey);
        if (!Files.isRegularFile(resolved)) {
            throw new ResourceNotFoundException("File not found");
        }
        try {
            return Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public OptionalLong size(String objectKey) {
        try {
            Path resolved = resolve(objectKey);
            return Files.isRegularFile(resolved) ? OptionalLong.of(Files.size(resolved)) : OptionalLong.empty();
        } catch (IOException | ResourceNotFoundException e) {
            return OptionalLong.empty();
        }
    }

    @Override
    public String probe() {
        return "Local disk " + root;
    }

    private Path resolve(String objectKey) {
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
        return resolved;
    }
}

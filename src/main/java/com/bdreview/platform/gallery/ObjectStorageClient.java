package com.bdreview.platform.gallery;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Infra boundary for object storage (spec §13): local disk ({@link DevObjectStorageClient}) or a
 * private S3-compatible bucket ({@link S3ObjectStorageClient}), chosen by {@code app.storage.mode}.
 * Upload and file URLs always point at {@link StorageController}, which checks access first.
 */
public interface ObjectStorageClient {
    String buildObjectKey(String folder, String ownerId, String filename);

    /** A signed upload URL for this key (see {@link StorageUrlSigner}). */
    String presignPutUrl(String objectKey);

    String cdnUrlFor(String objectKey);

    /** Reads back the raw bytes for an object key — used by admin-only proxy views that must never hand the caller a direct storage URL. */
    byte[] getObject(String objectKey);

    /** Server-side upload (admin panel forms, e.g. the homepage hero image) — returns the CDN URL. */
    default String putObject(String objectKey, byte[] content) {
        return putObject(objectKey, content, StorageContentTypes.forKey(objectKey));
    }

    String putObject(String objectKey, byte[] content, String contentType);

    /** The object's size, or empty when it doesn't exist. */
    OptionalLong size(String objectKey);

    /** A short-lived direct download URL, when the store supports one (S3); empty for local disk. */
    default Optional<URI> presignedGetUrl(String objectKey, Duration ttl) {
        return Optional.empty();
    }

    /** One line for System → Health; throws when the store is unreachable. */
    String probe();
}

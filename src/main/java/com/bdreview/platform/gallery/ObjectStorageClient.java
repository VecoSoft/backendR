package com.bdreview.platform.gallery;

/**
 * Infra boundary: pre-signed URL issuance for direct-to-bucket uploads (spec
 * §13). Implement against S3/R2 in a real deployment; the dev stub below just
 * fabricates URLs so the rest of the flow (metadata persistence, CDN URL
 * storage) can be exercised without a real bucket.
 */
public interface ObjectStorageClient {
    String buildObjectKey(String folder, String ownerId, String filename);
    String presignPutUrl(String objectKey);
    String cdnUrlFor(String objectKey);

    /** Reads back the raw bytes for an object key — used by admin-only proxy views that must never hand the caller a direct storage URL. */
    byte[] getObject(String objectKey);

    /** Server-side upload (admin panel forms, e.g. the homepage hero image) — returns the CDN URL. */
    String putObject(String objectKey, byte[] content);
}

package com.bdreview.platform.gallery;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ResourceNotFoundException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * S3-compatible object storage (DigitalOcean Spaces, or any S3 API) — {@code app.storage.mode=s3},
 * required in production.
 *
 * <p>The bucket stays <b>private</b>. Upload and file URLs keep pointing at {@link StorageController}
 * on this API, exactly as in local mode, so the access rules it enforces (NID feature gate,
 * pending/rejected/deleted photo visibility, ADMIN-only documents) always run first and the
 * {@code /api/v1/storage/files/...} URLs stored in the database keep working. An allowed GET is then
 * answered with a short-lived presigned URL (or streamed, with {@code app.storage.serve=proxy}).
 */
@Component
@ConditionalOnProperty(name = "app.storage.mode", havingValue = "s3")
public class S3ObjectStorageClient implements ObjectStorageClient {

    private final StorageUrlSigner urls;
    private final String bucket;
    private final String keyPrefix;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3ObjectStorageClient(StorageUrlSigner urls,
                                 @Value("${app.storage.s3.endpoint}") String endpoint,
                                 @Value("${app.storage.s3.region}") String region,
                                 @Value("${app.storage.s3.bucket}") String bucket,
                                 @Value("${app.storage.s3.access-key}") String accessKey,
                                 @Value("${app.storage.s3.secret-key}") String secretKey,
                                 @Value("${app.storage.s3.key-prefix:}") String keyPrefix,
                                 @Value("${app.storage.s3.path-style:false}") boolean pathStyle) {
        if (bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("STORAGE_MODE=s3 needs SPACES_BUCKET, SPACES_KEY and SPACES_SECRET");
        }
        this.urls = urls;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix.isBlank() ? "" : keyPrefix.replaceAll("^/+|/+$", "") + "/";
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .forcePathStyle(pathStyle)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .httpClient(UrlConnectionHttpClient.create())
                // SDK 2.30+ adds CRC checksums to every request by default; S3-compatible stores
                // (Spaces included) don't all accept them, so only send them when an API requires it.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .build();
    }

    @PreDestroy
    void close() {
        s3.close();
        presigner.close();
    }

    @Override
    public String buildObjectKey(String folder, String ownerId, String filename) {
        // Same folder convention as DevObjectStorageClient (spec §13).
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
        String key = clean(objectKey);
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(keyPrefix + key)
                        .contentType(contentType)
                        // Bucket objects are private: this only matters for the presigned responses.
                        .cacheControl("private, max-age=300")
                        .contentDisposition(StorageContentTypes.isInline(contentType) ? "inline" : "attachment")
                        .build(),
                RequestBody.fromBytes(content));
        return cdnUrlFor(key);
    }

    @Override
    public byte[] getObject(String objectKey) {
        String key = clean(objectKey);
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(keyPrefix + key).build())
                    .asByteArray();
        } catch (NoSuchKeyException e) {
            throw new ResourceNotFoundException("File not found");
        }
    }

    @Override
    public OptionalLong size(String objectKey) {
        try {
            return OptionalLong.of(s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(keyPrefix + clean(objectKey)).build())
                    .contentLength());
        } catch (NoSuchKeyException e) {
            return OptionalLong.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return OptionalLong.empty();
            }
            throw e;
        }
    }

    @Override
    public Optional<URI> presignedGetUrl(String objectKey, Duration ttl) {
        var request = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(keyPrefix + clean(objectKey)).build())
                .build();
        try {
            return Optional.of(presigner.presignGetObject(request).url().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String probe() {
        s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        return "S3 bucket " + bucket + (keyPrefix.isEmpty() ? "" : " (prefix " + keyPrefix + ")") + " reachable";
    }

    /** Same key rules as the local-disk store: no leading slash, no blank keys, no ".." segments. */
    private static String clean(String objectKey) {
        String cleaned = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        if (cleaned.isBlank()) {
            throw new BadRequestException("Missing object key");
        }
        for (String segment : cleaned.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new BadRequestException("Invalid object key");
            }
        }
        return cleaned;
    }
}

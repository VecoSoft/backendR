package com.bdreview.platform.gallery;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ResourceNotFoundException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.net.URLConnection;
import java.util.UUID;

/**
 * S3-compatible object storage (DigitalOcean Spaces, or any S3 API) for real deployments —
 * enabled with {@code app.storage.driver=s3}.
 *
 * <p>The bucket stays <b>private</b>: browsers never talk to it. Upload and file URLs keep pointing
 * at {@link StorageController} on this API, exactly as with {@link DevObjectStorageClient}, and the
 * controller streams bytes to and from the bucket here. That keeps the access rules the controller
 * enforces (NID feature gate, pending/rejected photo visibility, ADMIN-only claim documents) and the
 * {@code /api/v1/storage/files/...} URLs already stored in the database working unchanged.
 */
@Component
@ConditionalOnProperty(name = "app.storage.driver", havingValue = "s3")
public class S3ObjectStorageClient implements ObjectStorageClient {

    private final String baseUrl;
    private final String bucket;
    private final String keyPrefix;
    private final S3Client s3;

    public S3ObjectStorageClient(@Value("${app.storage.base-url}") String baseUrl,
                                 @Value("${app.storage.s3.endpoint}") String endpoint,
                                 @Value("${app.storage.s3.region}") String region,
                                 @Value("${app.storage.s3.bucket}") String bucket,
                                 @Value("${app.storage.s3.access-key}") String accessKey,
                                 @Value("${app.storage.s3.secret-key}") String secretKey,
                                 @Value("${app.storage.s3.key-prefix:}") String keyPrefix) {
        if (bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("app.storage.driver=s3 needs SPACES_BUCKET, SPACES_KEY and SPACES_SECRET");
        }
        this.baseUrl = baseUrl;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix.isBlank() ? "" : keyPrefix.replaceAll("^/+|/+$", "") + "/";
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @PreDestroy
    void close() {
        s3.close();
    }

    @Override
    public String buildObjectKey(String folder, String ownerId, String filename) {
        // Same folder convention as DevObjectStorageClient (spec §13).
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
        String key = clean(objectKey);
        String contentType = URLConnection.guessContentTypeFromName(key);
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(keyPrefix + key)
                        .contentType(contentType != null ? contentType : "application/octet-stream")
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

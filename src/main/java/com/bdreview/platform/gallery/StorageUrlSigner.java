package com.bdreview.platform.gallery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Builds and checks the upload and file URLs every {@link ObjectStorageClient} hands out. Upload
 * URLs carry {@code exp} + {@code sig} (HMAC-SHA256 over key and expiry), so only a key the API
 * issued can be written, and only for {@code app.storage.upload-url-ttl-minutes}. Before this, any
 * key could be PUT, which let anyone overwrite an approved photo. The key is derived from the JWT
 * secret, so no extra secret has to be configured.
 */
@Component
public class StorageUrlSigner {

    /** Generated WebP variants live under this prefix; it can't be uploaded to or fetched directly. */
    public static final String VARIANT_PREFIX = "_variants/";

    private final String baseUrl;
    private final Duration uploadTtl;
    private final byte[] key;

    public StorageUrlSigner(@Value("${app.storage.base-url}") String baseUrl,
                            @Value("${app.storage.upload-url-ttl-minutes}") long uploadTtlMinutes,
                            @Value("${app.jwt.secret}") String jwtSecret) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.uploadTtl = Duration.ofMinutes(uploadTtlMinutes);
        try {
            this.key = MessageDigest.getInstance("SHA-256").digest(("storage-upload:" + jwtSecret).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String uploadUrl(String objectKey) {
        long exp = Instant.now().plus(uploadTtl).getEpochSecond();
        return baseUrl + "/api/v1/storage/upload/" + objectKey + "?exp=" + exp + "&sig=" + sign(objectKey, exp);
    }

    public String fileUrl(String objectKey) {
        return baseUrl + "/api/v1/storage/files/" + objectKey;
    }

    /** True when {@code sig} was issued by {@link #uploadUrl} for this key and hasn't expired. */
    public boolean verifyUpload(String objectKey, Long exp, String sig) {
        if (exp == null || sig == null || Instant.now().getEpochSecond() > exp) {
            return false;
        }
        return MessageDigest.isEqual(sign(objectKey, exp).getBytes(StandardCharsets.US_ASCII),
                sig.getBytes(StandardCharsets.US_ASCII));
    }

    private String sign(String objectKey, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = mac.doFinal((objectKey + "\n" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

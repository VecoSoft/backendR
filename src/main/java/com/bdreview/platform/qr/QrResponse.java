package com.bdreview.platform.qr;

import java.time.Instant;
import java.util.UUID;

/** Owner-facing — what the "Business QR" dashboard section needs. */
public record QrResponse(UUID businessId, String qrToken, QrStatus status, Instant createdAt) {
    public static QrResponse from(BusinessQr qr) {
        return new QrResponse(qr.getBusinessId(), qr.getQrToken(), qr.getStatus(), qr.getCreatedAt());
    }
}

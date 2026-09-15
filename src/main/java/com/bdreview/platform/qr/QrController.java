package com.bdreview.platform.qr;

import com.bdreview.platform.common.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Business QR V1. The owner endpoint is get-or-create and idempotent; the
 * resolution endpoint is public and registered as such in {@code auth.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1")
public class QrController {

    private final QrService qrService;

    public QrController(QrService qrService) {
        this.qrService = qrService;
    }

    /** Owner-only. First call creates the permanent QR; every later call returns the same one. */
    @GetMapping("/businesses/{businessId}/qr")
    public QrResponse getOrCreate(@PathVariable UUID businessId) {
        return qrService.getOrCreateForOwner(CurrentUser.id(), businessId);
    }

    /** Public. Resolves a scanned token to the business's current slug — never a cached one. */
    @GetMapping("/qr/{token}")
    public QrService.QrResolveResponse resolve(@PathVariable String token) {
        return qrService.resolvePublic(token);
    }
}

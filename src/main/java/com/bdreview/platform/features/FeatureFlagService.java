package com.bdreview.platform.features;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.moderation.AuditLogService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime feature flags (V63). Each {@link PlatformFeature} has a deployment default
 * (application.yml / env var) that an ADMIN can override from the admin panel's
 * System → Settings page; overrides live in {@code platform_setting}, so flipping one needs no
 * restart and applies within seconds on every instance.
 */
@Service
public class FeatureFlagService {

    public static final String DEFAULT_MAINTENANCE_MESSAGE =
            "Jachai is down for scheduled maintenance. Please check back soon.";

    /** One flag as the admin settings page shows it. */
    public record FlagView(PlatformFeature feature, boolean defaultValue, Boolean override, boolean effective,
                           String message, UUID updatedBy, Instant updatedAt) {
        public String key() {
            return feature.name();
        }
    }

    private final PlatformSettingStore store;
    private final Environment environment;
    private final AuditLogService auditLogService;

    public FeatureFlagService(PlatformSettingStore store, Environment environment, AuditLogService auditLogService) {
        this.store = store;
        this.environment = environment;
        this.auditLogService = auditLogService;
    }

    public boolean isEnabled(PlatformFeature feature) {
        return store.get(feature.name()).map(PlatformSettingStore.Row::enabled).orElseGet(() -> defaultOf(feature));
    }

    public boolean defaultOf(PlatformFeature feature) {
        return environment.getProperty(feature.configKey(), Boolean.class, feature.fallbackDefault());
    }

    /** The message shown while maintenance mode is on (admin-provided, or a generic default). */
    public String maintenanceMessage() {
        String message = store.get(PlatformFeature.MAINTENANCE_MODE.name()).map(PlatformSettingStore.Row::message).orElse(null);
        return message == null || message.isBlank() ? DEFAULT_MAINTENANCE_MESSAGE : message;
    }

    public List<FlagView> all() {
        return Arrays.stream(PlatformFeature.values()).map(f -> {
            var row = store.get(f.name());
            return new FlagView(f, defaultOf(f), row.map(PlatformSettingStore.Row::enabled).orElse(null), isEnabled(f),
                    row.map(PlatformSettingStore.Row::message).orElse(null),
                    row.map(PlatformSettingStore.Row::updatedBy).orElse(null),
                    row.map(PlatformSettingStore.Row::updatedAt).orElse(null));
        }).toList();
    }

    /** Effective value of every flag — for the public settings endpoint. */
    public Map<PlatformFeature, Boolean> effective() {
        Map<PlatformFeature, Boolean> out = new LinkedHashMap<>();
        for (PlatformFeature f : PlatformFeature.values()) {
            out.put(f, isEnabled(f));
        }
        return out;
    }

    /**
     * Sets ({@code override} true/false) or clears ({@code null} → back to the deployment default)
     * one flag, audited with the admin's reason and the before/after state.
     */
    public void set(PlatformFeature feature, Boolean override, String message, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        String cleanMessage = message == null || message.isBlank() ? null : message.trim();
        if (cleanMessage != null && cleanMessage.length() > 500) {
            throw new BadRequestException("The message can be at most 500 characters.");
        }
        Map<String, Object> before = snapshot(feature);
        if (override == null) {
            store.clear(feature.name());
        } else {
            store.put(feature.name(), override, cleanMessage, CurrentUser.idOrNull());
        }
        auditLogService.record("FEATURE_FLAG", null, "FEATURE_FLAG_" + feature.name(), reason.trim(), before, snapshot(feature));
    }

    private Map<String, Object> snapshot(PlatformFeature feature) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("flag", feature.name());
        s.put("override", store.get(feature.name()).map(PlatformSettingStore.Row::enabled).orElse(null));
        s.put("effective", isEnabled(feature));
        if (feature == PlatformFeature.MAINTENANCE_MODE) {
            s.put("message", store.get(feature.name()).map(PlatformSettingStore.Row::message).orElse(null));
        }
        return s;
    }

    // ---- kept for the existing NID call sites ----

    /**
     * NID (national ID) verification. OFF by default. While off: NID uploads are refused and
     * NID objects in storage are not served, and no flow may require NID.
     */
    public boolean nidVerificationEnabled() {
        return isEnabled(PlatformFeature.NID_VERIFICATION);
    }

    public boolean nidVerificationDefault() {
        return defaultOf(PlatformFeature.NID_VERIFICATION);
    }
}

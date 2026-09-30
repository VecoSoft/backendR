package com.bdreview.platform.community.settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Runtime feature flags. Each flag has a deployment default (application.yml / env var) that an
 * ADMIN can override from the admin panel's Community settings → Feature flags; the override is
 * stored in the cached community_settings document, so flipping it needs no restart.
 */
@Service
public class FeatureFlagService {

    private final CommunitySettingsService settingsService;
    private final boolean nidVerificationDefault;

    public FeatureFlagService(CommunitySettingsService settingsService,
                              @Value("${features.nid-verification.enabled:false}") boolean nidVerificationDefault) {
        this.settingsService = settingsService;
        this.nidVerificationDefault = nidVerificationDefault;
    }

    /**
     * NID (national ID) verification. OFF by default. While off: every NID endpoint answers 404
     * "Feature disabled" and accepts no files, NID objects in storage are not served, and no flow
     * may require NID (see nid.NidFeatureGuard).
     */
    public boolean nidVerificationEnabled() {
        Boolean override = settingsService.settings().getFeatures().getNidVerificationEnabled();
        return override != null ? override : nidVerificationDefault;
    }

    public boolean nidVerificationDefault() {
        return nidVerificationDefault;
    }
}

package com.bdreview.platform.adminconfig;

import com.bdreview.platform.review.ReviewPolicyHolder;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** Plugs the admin's review edit window into {@link ReviewPolicyHolder} (read through the config cache). */
@Component
public class ReviewPolicyBridge {

    private final AdminConfigService adminConfig;

    public ReviewPolicyBridge(AdminConfigService adminConfig) {
        this.adminConfig = adminConfig;
    }

    @PostConstruct
    void install() {
        ReviewPolicyHolder.setEditWindowSource(() -> adminConfig.reviewPolicy().getEditWindowHours());
    }
}

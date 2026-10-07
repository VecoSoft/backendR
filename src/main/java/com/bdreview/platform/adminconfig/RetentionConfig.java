package com.bdreview.platform.adminconfig;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * System → Health → Data retention (V69): admin overrides of the retention periods, in days. A null
 * field means "use the deployment default" (application.yml {@code retention.*}, env-configurable).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RetentionConfig {
    private Integer notificationsReadDays;
    private Integer notificationsUnreadDays;
    private Integer searchLogDays;
    private Integer loginEventsDays;
    private Integer jobRunsDays;
    private Integer auditLogDays;
    private Integer softDeletedContentDays;
}

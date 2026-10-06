package com.bdreview.platform.adminconfig;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Admin → Commerce settings (V65). Every field has a built-in default used until an admin saves. */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommerceConfig {
    /** A PENDING ("new") order the business never answered is auto-cancelled after this many minutes. Was a hardcoded 24h. */
    private int orderAutoCancelMinutes = 24 * 60;
    /** A CONFIRMED booking is auto-closed (completed / no-show) this many minutes after its slot ends. Was a hardcoded 1h. */
    private int bookingNoShowGraceMinutes = 60;
    /** Max ACTIVE (not yet expired) offers one business may run at a time. */
    private int maxActiveOffersPerBusiness = 10;
}

package com.bdreview.platform.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * Which staff members provide which service — a booking may only pick a
 * qualified, active staff member. {@code durationMinutes}/{@code bufferMinutes}
 * are optional per-staff overrides of the service's own default (a given
 * staff member may be faster or slower at the same service); null means
 * "use the service's default."
 */
@Entity
@Table(name = "staff_service")
@Getter
@NoArgsConstructor
public class StaffServiceLink {

    @EmbeddedId
    private Id id;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "buffer_minutes")
    private Integer bufferMinutes;

    public StaffServiceLink(UUID teamMemberId, UUID serviceId) {
        this.id = new Id(teamMemberId, serviceId);
    }

    public StaffServiceLink(UUID teamMemberId, UUID serviceId, Integer durationMinutes, Integer bufferMinutes) {
        this.id = new Id(teamMemberId, serviceId);
        this.durationMinutes = durationMinutes;
        this.bufferMinutes = bufferMinutes;
    }

    @Embeddable
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor @AllArgsConstructor
    public static class Id implements Serializable {
        @Column(name = "team_member_id")
        private UUID teamMemberId;

        @Column(name = "service_id")
        private UUID serviceId;
    }
}

package com.bdreview.platform.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StaffServiceLinkRepository extends JpaRepository<StaffServiceLink, StaffServiceLink.Id> {

    @Query("select l.id.serviceId from StaffServiceLink l where l.id.teamMemberId = :teamMemberId")
    List<UUID> findServiceIdsByTeamMemberId(@Param("teamMemberId") UUID teamMemberId);

    /** Full rows (incl. duration/buffer overrides) for one staff member — the Staff Schedules editor. */
    @Query("select l from StaffServiceLink l where l.id.teamMemberId = :teamMemberId")
    List<StaffServiceLink> findAssignmentsByTeamMemberId(@Param("teamMemberId") UUID teamMemberId);

    @Query("select l.id.teamMemberId from StaffServiceLink l where l.id.serviceId = :serviceId")
    List<UUID> findTeamMemberIdsByServiceId(@Param("serviceId") UUID serviceId);

    @Modifying
    @Query("delete from StaffServiceLink l where l.id.teamMemberId = :teamMemberId")
    void deleteByTeamMemberId(@Param("teamMemberId") UUID teamMemberId);
}

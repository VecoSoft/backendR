package com.bdreview.platform.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface StaffTimeOffRepository extends JpaRepository<StaffTimeOff, UUID> {

    List<StaffTimeOff> findByTeamMemberIdOrderByStartDateAsc(UUID teamMemberId);

    @Query("select case when count(t) > 0 then true else false end from StaffTimeOff t " +
            "where t.teamMemberId = :teamMemberId and :date between t.startDate and t.endDate")
    boolean existsOnDate(@Param("teamMemberId") UUID teamMemberId, @Param("date") LocalDate date);

    void deleteByIdAndTeamMemberId(UUID id, UUID teamMemberId);
}

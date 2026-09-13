package com.bdreview.platform.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffWeeklyScheduleRepository extends JpaRepository<StaffWeeklySchedule, UUID> {

    List<StaffWeeklySchedule> findByTeamMemberId(UUID teamMemberId);

    Optional<StaffWeeklySchedule> findByTeamMemberIdAndDayOfWeek(UUID teamMemberId, DayOfWeek dayOfWeek);

    /**
     * A bulk {@code @Modifying} query, not a derived delete: a derived
     * {@code deleteByTeamMemberId} queues entity removal via the persistence
     * context, and Hibernate's default flush ordering runs inserts BEFORE
     * deletes — replacing a day (delete old row, insert new one with the same
     * team_member_id+day_of_week key) would then spuriously violate the
     * unique constraint. A bulk query executes immediately instead.
     */
    @Modifying
    @Query("delete from StaffWeeklySchedule s where s.teamMemberId = :teamMemberId")
    void deleteByTeamMemberId(@Param("teamMemberId") UUID teamMemberId);
}

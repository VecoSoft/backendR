package com.bdreview.platform.auth;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Resolves a Community-facing pseudonymous id back to the real user — see V46's migration comment. */
    Optional<User> findByCommunityProfileId(UUID communityProfileId);

    /**
     * Phone number is no longer globally unique — one phone can back at most
     * one CONSUMER row and one BUSINESS_OWNER row (see V17 migration), so
     * every lookup/existence-check must be scoped by role. Deliberately no
     * unscoped {@code findByPhoneNumber}/{@code existsByPhoneNumber} here
     * anymore — those would throw once a phone number legitimately has two
     * rows.
     */
    Optional<User> findByPhoneNumberAndRole(String phoneNumber, UserRole role);

    boolean existsByPhoneNumberAndRole(String phoneNumber, UserRole role);

    /** Main-site login resolves against CONSUMER and ADMIN rows for a phone — never BUSINESS_OWNER (that has its own login surface). */
    List<User> findAllByPhoneNumberAndRoleIn(String phoneNumber, Collection<UserRole> roles);

    /** Batched "which of these business owners are still admin-placeholder accounts" — see BusinessService#claimedByOwner. */
    @Query("SELECT u.id FROM User u WHERE u.id IN :ids AND u.role = :role")
    List<UUID> findIdsByIdInAndRole(@Param("ids") Collection<UUID> ids, @Param("role") UserRole role);

    // -----------------------------------------------------------------
    // "Join Community" pseudonymous identity — see community.CommunityUsernameService.
    // -----------------------------------------------------------------
    boolean existsByCommunityUsernameIgnoreCase(String communityUsername);

    Optional<User> findByCommunityUsernameIgnoreCase(String communityUsername);

    // -----------------------------------------------------------------
    // Admin panel (com.bdreview.platform.admin) — read-side search/listing
    // only; account mutation still goes through UserRepository#save as
    // everywhere else in the codebase.
    // -----------------------------------------------------------------
    Page<User> findByRole(UserRole role, Pageable pageable);

    @Query("""
            SELECT u FROM User u
            WHERE (:query IS NULL OR :query = ''
                   OR LOWER(u.name) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR u.phoneNumber LIKE CONCAT('%', :query, '%'))
              AND (:role IS NULL OR u.role = :role)
            """)
    Page<User> search(@Param("query") String query, @Param("role") UserRole role, Pageable pageable);

    /**
     * V63 admin list with the account-status filter: {@code status} null (any), ACTIVE (no
     * restriction in force), SUSPENDED (a suspension and no ban in force) or BANNED.
     */
    @Query("""
            SELECT u FROM User u
            WHERE (:query IS NULL OR :query = ''
                   OR LOWER(u.name) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR u.phoneNumber LIKE CONCAT('%', :query, '%'))
              AND (:role IS NULL OR u.role = :role)
              AND (:status IS NULL
                   OR (:status = 'BANNED' AND EXISTS (SELECT 1 FROM UserRestriction r WHERE r.userId = u.id
                           AND r.type = com.bdreview.platform.accountcontrol.UserRestriction.Type.BAN
                           AND r.liftedAt IS NULL AND r.startsAt <= :now))
                   OR (:status = 'SUSPENDED'
                       AND EXISTS (SELECT 1 FROM UserRestriction r WHERE r.userId = u.id
                           AND r.type = com.bdreview.platform.accountcontrol.UserRestriction.Type.SUSPEND
                           AND r.liftedAt IS NULL AND r.startsAt <= :now AND r.endsAt > :now)
                       AND NOT EXISTS (SELECT 1 FROM UserRestriction r WHERE r.userId = u.id
                           AND r.type = com.bdreview.platform.accountcontrol.UserRestriction.Type.BAN
                           AND r.liftedAt IS NULL AND r.startsAt <= :now))
                   OR (:status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM UserRestriction r WHERE r.userId = u.id
                           AND r.liftedAt IS NULL AND r.startsAt <= :now AND (r.endsAt IS NULL OR r.endsAt > :now))))
            ORDER BY u.createdAt DESC
            """)
    Page<User> adminSearch(@Param("query") String query, @Param("role") UserRole role, @Param("status") String status,
                           @Param("now") java.time.Instant now, Pageable pageable);

    // -----------------------------------------------------------------
    // Community staff (V56 staff_role) — see admin.config.AdminAuthenticationProvider.
    // -----------------------------------------------------------------
    List<User> findAllByPhoneNumberAndStaffRole(String phoneNumber, String staffRole);

    List<User> findAllByStaffRoleOrderByNameAsc(String staffRole);

    /** Role-assignment page only: every account (consumer/owner) behind one phone number. */
    List<User> findAllByPhoneNumberAndRoleNot(String phoneNumber, UserRole role);
}

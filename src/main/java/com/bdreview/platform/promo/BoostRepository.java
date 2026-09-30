package com.bdreview.platform.promo;

import com.bdreview.platform.promo.PromoEnums.BoostStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BoostRepository extends JpaRepository<Boost, UUID> {

    List<Boost> findByBusinessIdOrderByCreatedAtDesc(UUID businessId);

    List<Boost> findByPostIdOrderByCreatedAtDesc(UUID postId);

    List<Boost> findByStatusOrderByCreatedAtAsc(BoostStatus status);

    List<Boost> findByStatusInOrderByCreatedAtDesc(Collection<BoostStatus> statuses);

    /** Everything that could be served right now (targeting/pacing/cap are applied in SponsoredService). */
    @Query("""
            SELECT b FROM Boost b
            WHERE b.status = :active AND b.startAt <= :now AND b.endAt > :now
            """)
    List<Boost> findLiveWithStatus(@Param("now") Instant now, @Param("active") BoostStatus active);

    default List<Boost> findLive(Instant now) {
        return findLiveWithStatus(now, BoostStatus.ACTIVE);
    }

    @Query("SELECT b FROM Boost b WHERE b.status IN :statuses AND b.endAt <= :now")
    List<Boost> findFinished(@Param("statuses") Collection<BoostStatus> statuses, @Param("now") Instant now);

    boolean existsByPostIdAndStatusIn(UUID postId, Collection<BoostStatus> statuses);

    @Modifying
    @Transactional
    @Query("UPDATE Boost b SET b.impressionsServed = b.impressionsServed + :n WHERE b.id = :id")
    void addImpressions(@Param("id") UUID id, @Param("n") int n);

    /** Revenue view: verified payments per day and package (refunds excluded). */
    @Query(value = """
            SELECT CAST(date_trunc('day', payment_verified_at AT TIME ZONE 'Asia/Dhaka') AS date) AS day,
                   package_name, count(*) AS boosts, COALESCE(sum(paid_amount), 0) AS amount
            FROM boost
            WHERE payment_verified_at IS NOT NULL AND payment_verified_at >= :since AND status <> 'REFUNDED'
            GROUP BY 1, 2 ORDER BY 1 DESC, 2
            """, nativeQuery = true)
    List<Object[]> revenueSince(@Param("since") Instant since);
}

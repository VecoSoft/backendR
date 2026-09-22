package com.bdreview.platform.offer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfferClaimRepository extends JpaRepository<OfferClaim, UUID> {

    boolean existsByRedemptionCode(String redemptionCode);

    Optional<OfferClaim> findByRedemptionCode(String redemptionCode);

    /** Per-user claim-limit check — see OfferService#claim. */
    long countByOfferIdAndUserIdAndStatusNot(UUID offerId, UUID userId, OfferClaimStatus excludedStatus);

    /** Total-claims-so-far check (independent of maxTotalRedemptions, which counts *redemptions* not claims — see the offer limits section of the plan). */
    long countByOfferIdAndStatusNot(UUID offerId, OfferClaimStatus excludedStatus);

    List<OfferClaim> findByOfferIdAndUserIdOrderByClaimedAtDesc(UUID offerId, UUID userId);

    /** "My Claimed Offers" page. */
    Page<OfferClaim> findByUserIdOrderByClaimedAtDesc(UUID userId, Pageable pageable);
}

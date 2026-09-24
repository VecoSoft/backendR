package com.bdreview.platform.offer;

import com.bdreview.platform.business.Area;
import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.business.City;
import com.bdreview.platform.catalog.MenuItemRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.moderation.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Covers ownership/verification gating, claim limits, redemption-code uniqueness/no-reuse, and derived expiry. */
@ExtendWith(MockitoExtension.class)
class OfferServiceTest {

    @Mock OfferRepository offerRepository;
    @Mock OfferClaimRepository claimRepository;
    @Mock OfferSaveRepository saveRepository;
    @Mock BusinessRepository businessRepository;
    @Mock MenuItemRepository menuItemRepository;
    @Mock AuditLogService auditLogService;
    @Mock OfferNotifier offerNotifier;

    OfferService service;
    UUID ownerId;
    UUID businessId;

    @BeforeEach
    void setUp() {
        service = new OfferService(offerRepository, claimRepository, saveRepository, businessRepository, menuItemRepository, auditLogService, offerNotifier);
        ownerId = UUID.randomUUID();
        businessId = UUID.randomUUID();
        lenient().when(offerRepository.save(any())).thenAnswer(inv -> {
            Offer o = inv.getArgument(0);
            if (o.getId() == null) o.setId(UUID.randomUUID());
            return o;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** requireRole("ADMIN") reads this from the security context — admin-only tests call this first. */
    private void asAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private Business verifiedBusiness() {
        City city = City.builder().id(UUID.randomUUID()).name("Dhaka").build();
        Area area = Area.builder().id(UUID.randomUUID()).name("Dhanmondi").city(city).build();
        return Business.builder().id(businessId).ownerUserId(ownerId).name("ABC Restaurant")
                .area(area).verified(true).build();
    }

    private CreateOfferRequest percentOfferRequest() {
        return new CreateOfferRequest(businessId, "20% Off Family Combo", OfferType.PERCENTAGE_DISCOUNT,
                BigDecimal.valueOf(20), BigDecimal.valueOf(1000), BigDecimal.valueOf(800),
                "Family combo discount", "One redemption per customer", null,
                Instant.now(), Instant.now().plus(7, ChronoUnit.DAYS), OfferAvailability.BOTH, 100, 1, null);
    }

    @Test
    void unverifiedBusinessCannotCreateAnOffer() {
        Business unverified = Business.builder().id(businessId).ownerUserId(ownerId).verified(false).build();
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(unverified));

        assertThatThrownBy(() -> service.createOffer(ownerId, percentOfferRequest()))
                .isInstanceOf(ForbiddenException.class);
        verify(offerRepository, never()).save(any());
    }

    @Test
    void nonOwnerCannotCreateAnOfferForSomeoneElsesBusiness() {
        Business business = verifiedBusiness();
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business));

        assertThatThrownBy(() -> service.createOffer(UUID.randomUUID(), percentOfferRequest()))
                .isInstanceOf(ForbiddenException.class);
        verify(offerRepository, never()).save(any());
    }

    @Test
    void percentageDiscountRequiresADiscountValue() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));
        CreateOfferRequest request = new CreateOfferRequest(businessId, "Some discount", OfferType.PERCENTAGE_DISCOUNT,
                null, null, null, null, null, null, Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS), OfferAvailability.BOTH, null, null, null);

        assertThatThrownBy(() -> service.createOffer(ownerId, request)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void freeItemOfferDoesNotRequireADiscountValue() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));
        CreateOfferRequest request = new CreateOfferRequest(businessId, "Free Coffee", OfferType.FREE_ITEM,
                null, null, null, null, null, null, Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS), OfferAvailability.IN_STORE, null, null, null);

        OfferResponse response = service.createOffer(ownerId, request);

        assertThat(response.status()).isEqualTo(OfferStatus.DRAFT);
        assertThat(response.discountValue()).isNull();
    }

    @Test
    void validUntilMustBeAfterValidFrom() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));
        Instant now = Instant.now();
        CreateOfferRequest request = new CreateOfferRequest(businessId, "Bad dates", OfferType.OTHER,
                null, null, null, null, null, null, now, now.minus(1, ChronoUnit.HOURS), OfferAvailability.BOTH, null, null, null);

        assertThatThrownBy(() -> service.createOffer(ownerId, request)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void newOfferStartsAsDraft() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));

        OfferResponse response = service.createOffer(ownerId, percentOfferRequest());

        assertThat(response.status()).isEqualTo(OfferStatus.DRAFT);
    }

    @Test
    void submitTransitionsDraftToActive() {
        Offer draft = Offer.builder().id(UUID.randomUUID()).businessId(businessId).title("x")
                .offerType(OfferType.OTHER).status(OfferStatus.DRAFT)
                .validFrom(Instant.now()).validUntil(Instant.now().plus(1, ChronoUnit.DAYS)).build();
        when(offerRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));

        OfferResponse response = service.submitOffer(ownerId, draft.getId());

        assertThat(response.status()).isEqualTo(OfferStatus.ACTIVE);
    }

    @Test
    void onlyOwnerCanSubmitTheirOffer() {
        Offer draft = Offer.builder().id(UUID.randomUUID()).businessId(businessId).title("x")
                .offerType(OfferType.OTHER).status(OfferStatus.DRAFT)
                .validFrom(Instant.now()).validUntil(Instant.now().plus(1, ChronoUnit.DAYS)).build();
        when(offerRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));

        assertThatThrownBy(() -> service.submitOffer(UUID.randomUUID(), draft.getId())).isInstanceOf(ForbiddenException.class);
    }

    // -----------------------------------------------------------------
    // Claim
    // -----------------------------------------------------------------

    private Offer activeOffer(UUID id) {
        return Offer.builder().id(id).businessId(businessId).title("20% Off").offerType(OfferType.PERCENTAGE_DISCOUNT)
                .discountValue(BigDecimal.valueOf(20)).status(OfferStatus.ACTIVE)
                .validFrom(Instant.now().minus(1, ChronoUnit.HOURS)).validUntil(Instant.now().plus(7, ChronoUnit.DAYS))
                .build();
    }

    @Test
    void businessOwnerCannotClaimTheirOwnOffer() {
        UUID offerId = UUID.randomUUID();
        when(offerRepository.findById(offerId)).thenReturn(Optional.of(activeOffer(offerId)));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));

        assertThatThrownBy(() -> service.claimOffer(ownerId, offerId)).isInstanceOf(BadRequestException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    void expiredOfferCannotBeClaimed() {
        UUID offerId = UUID.randomUUID();
        Offer expired = Offer.builder().id(offerId).businessId(businessId).title("Old").offerType(OfferType.OTHER)
                .status(OfferStatus.ACTIVE).validFrom(Instant.now().minus(10, ChronoUnit.DAYS))
                .validUntil(Instant.now().minus(1, ChronoUnit.HOURS)).build();
        when(offerRepository.findById(offerId)).thenReturn(Optional.of(expired));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));

        assertThatThrownBy(() -> service.claimOffer(UUID.randomUUID(), offerId)).isInstanceOf(BadRequestException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    void cancelledOfferCannotBeClaimed() {
        UUID offerId = UUID.randomUUID();
        Offer cancelled = Offer.builder().id(offerId).businessId(businessId).title("x").offerType(OfferType.OTHER)
                .status(OfferStatus.CANCELLED).validFrom(Instant.now().minus(1, ChronoUnit.DAYS))
                .validUntil(Instant.now().plus(1, ChronoUnit.DAYS)).build();
        when(offerRepository.findById(offerId)).thenReturn(Optional.of(cancelled));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));

        assertThatThrownBy(() -> service.claimOffer(UUID.randomUUID(), offerId)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void perUserClaimLimitIsEnforced() {
        UUID offerId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Offer offer = activeOffer(offerId);
        offer.setMaxRedemptionsPerUser(1);
        when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));
        when(claimRepository.countByOfferIdAndUserIdAndStatusNot(offerId, customerId, OfferClaimStatus.CANCELLED)).thenReturn(1L);

        assertThatThrownBy(() -> service.claimOffer(customerId, offerId)).isInstanceOf(BadRequestException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    void totalClaimLimitIsEnforced() {
        UUID offerId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Offer offer = activeOffer(offerId);
        offer.setMaxTotalRedemptions(100);
        when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));
        when(claimRepository.countByOfferIdAndStatusNot(offerId, OfferClaimStatus.CANCELLED)).thenReturn(100L);

        assertThatThrownBy(() -> service.claimOffer(customerId, offerId)).isInstanceOf(BadRequestException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    void successfulClaimGeneratesAUniqueCodeAndIncrementsClaimCount() {
        UUID offerId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(offerRepository.findById(offerId)).thenReturn(Optional.of(activeOffer(offerId)));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));
        when(claimRepository.existsByRedemptionCode(any())).thenReturn(false);
        when(claimRepository.save(any())).thenAnswer(inv -> {
            OfferClaim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        OfferClaimResponse response = service.claimOffer(customerId, offerId);

        assertThat(response.redemptionCode()).isNotBlank();
        assertThat(response.status()).isEqualTo(OfferClaimStatus.CLAIMED);
        verify(offerRepository).adjustClaimCount(offerId, 1);
        verify(offerNotifier).offerClaimed(ownerId, offerId, "20% Off");
    }

    // -----------------------------------------------------------------
    // Redeem
    // -----------------------------------------------------------------

    @Test
    void redeemRequiresBusinessOwnership() {
        UUID offerId = UUID.randomUUID();
        OfferClaim claim = OfferClaim.builder().id(UUID.randomUUID()).offerId(offerId).userId(UUID.randomUUID())
                .redemptionCode("ABCD1234").status(OfferClaimStatus.CLAIMED).build();
        when(claimRepository.findByRedemptionCode("ABCD1234")).thenReturn(Optional.of(claim));
        when(offerRepository.findById(offerId)).thenReturn(Optional.of(activeOffer(offerId)));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));

        assertThatThrownBy(() -> service.redeemOffer(UUID.randomUUID(), new RedeemOfferRequest("ABCD1234")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void invalidRedemptionCodeIsRejected() {
        when(claimRepository.findByRedemptionCode("NOPE0000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.redeemOffer(ownerId, new RedeemOfferRequest("NOPE0000")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void alreadyRedeemedCodeCannotBeReused() {
        UUID offerId = UUID.randomUUID();
        OfferClaim claim = OfferClaim.builder().id(UUID.randomUUID()).offerId(offerId).userId(UUID.randomUUID())
                .redemptionCode("ABCD1234").status(OfferClaimStatus.REDEEMED).redeemedAt(Instant.now()).build();
        when(claimRepository.findByRedemptionCode("ABCD1234")).thenReturn(Optional.of(claim));
        when(offerRepository.findById(offerId)).thenReturn(Optional.of(activeOffer(offerId)));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));

        assertThatThrownBy(() -> service.redeemOffer(ownerId, new RedeemOfferRequest("ABCD1234")))
                .isInstanceOf(BadRequestException.class);
        verify(offerRepository, never()).adjustRedemptionCount(any(), anyInt());
    }

    @Test
    void successfulRedemptionFlipsClaimStatusAndIncrementsRedemptionCount() {
        UUID offerId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        OfferClaim claim = OfferClaim.builder().id(UUID.randomUUID()).offerId(offerId).userId(customerId)
                .redemptionCode("ABCD1234").status(OfferClaimStatus.CLAIMED).build();
        when(claimRepository.findByRedemptionCode("ABCD1234")).thenReturn(Optional.of(claim));
        when(offerRepository.findById(offerId)).thenReturn(Optional.of(activeOffer(offerId)));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));
        when(claimRepository.save(any())).thenReturn(claim);

        OfferClaimResponse response = service.redeemOffer(ownerId, new RedeemOfferRequest("abcd1234"));

        assertThat(response.status()).isEqualTo(OfferClaimStatus.REDEEMED);
        verify(offerRepository).adjustRedemptionCount(offerId, 1);
        verify(offerNotifier).offerRedeemed(customerId, offerId, "20% Off");
    }

    // -----------------------------------------------------------------
    // Admin
    // -----------------------------------------------------------------

    @Test
    void adminApproveRequiresPendingApprovalStatus() {
        asAdmin();
        UUID offerId = UUID.randomUUID();
        Offer active = activeOffer(offerId);
        when(offerRepository.findById(offerId)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.adminApprove(offerId)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void adminApproveActivatesAPendingOffer() {
        asAdmin();
        UUID offerId = UUID.randomUUID();
        Offer pending = Offer.builder().id(offerId).businessId(businessId).title("x").offerType(OfferType.OTHER)
                .status(OfferStatus.PENDING_APPROVAL).validFrom(Instant.now()).validUntil(Instant.now().plus(1, ChronoUnit.DAYS)).build();
        when(offerRepository.findById(offerId)).thenReturn(Optional.of(pending));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));

        OfferResponse response = service.adminApprove(offerId);

        assertThat(response.status()).isEqualTo(OfferStatus.ACTIVE);
        verify(offerNotifier).offerApproved(ownerId, offerId, "x");
    }

    @Test
    void adminRejectSetsRejectedStatusAndReason() {
        asAdmin();
        UUID offerId = UUID.randomUUID();
        Offer pending = Offer.builder().id(offerId).businessId(businessId).title("x").offerType(OfferType.OTHER)
                .status(OfferStatus.PENDING_APPROVAL).validFrom(Instant.now()).validUntil(Instant.now().plus(1, ChronoUnit.DAYS)).build();
        when(offerRepository.findById(offerId)).thenReturn(Optional.of(pending));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));

        OfferResponse response = service.adminReject(offerId, new RejectOfferRequest("Missing required info"));

        assertThat(response.status()).isEqualTo(OfferStatus.REJECTED);
        assertThat(response.rejectionReason()).isEqualTo("Missing required info");
    }

    // -----------------------------------------------------------------
    // Derived expiry
    // -----------------------------------------------------------------

    @Test
    void effectiveStatusShowsExpiredOnceValidUntilHasPassedEvenIfStoredStatusIsStillActive() {
        Offer offer = Offer.builder().id(UUID.randomUUID()).businessId(businessId).title("x").offerType(OfferType.OTHER)
                .status(OfferStatus.ACTIVE).validFrom(Instant.now().minus(2, ChronoUnit.DAYS))
                .validUntil(Instant.now().minus(1, ChronoUnit.HOURS)).build();

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.ACTIVE);
        assertThat(offer.getEffectiveStatus()).isEqualTo(OfferStatus.EXPIRED);
    }

    @Test
    void getOfferOnAnExpiredOrNonActiveOfferIsHiddenFromAnAnonymousViewer() {
        UUID offerId = UUID.randomUUID();
        Offer draft = Offer.builder().id(offerId).businessId(businessId).title("x").offerType(OfferType.OTHER)
                .status(OfferStatus.DRAFT).validFrom(Instant.now()).validUntil(Instant.now().plus(1, ChronoUnit.DAYS)).build();
        when(offerRepository.findById(offerId)).thenReturn(Optional.of(draft));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(verifiedBusiness()));

        assertThatThrownBy(() -> service.getOffer(offerId, null)).isInstanceOf(ResourceNotFoundException.class);
    }
}

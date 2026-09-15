package com.bdreview.platform.qr;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure-JUnit coverage of Business QR V1's core rules: get-or-create
 * idempotency, owner-only access, and that resolution always reads the
 * business's *current* slug (never a value cached on the QR row itself).
 */
@ExtendWith(MockitoExtension.class)
class QrServiceTest {

    @Mock BusinessQrRepository qrRepository;
    @Mock BusinessRepository businessRepository;

    QrService qrService;

    UUID businessId;
    UUID ownerUserId;

    @BeforeEach
    void setUp() {
        qrService = new QrService(qrRepository, businessRepository, null);
        qrService.self = qrService; // no Spring proxy here, so self-invocation is wired directly
        businessId = UUID.randomUUID();
        ownerUserId = UUID.randomUUID();
    }

    private Business business(UUID id, UUID ownerId, String slug, boolean deleted) {
        return Business.builder().id(id).ownerUserId(ownerId).slug(slug)
                .deletedAt(deleted ? java.time.Instant.now() : null).build();
    }

    @Test
    void getOrCreateInsertsWhenNoneExists() {
        when(qrRepository.findByBusinessId(businessId)).thenReturn(Optional.empty());
        when(qrRepository.existsByQrToken(any())).thenReturn(false);
        when(qrRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        BusinessQr created = qrService.getOrCreate(businessId);

        assertThat(created.getBusinessId()).isEqualTo(businessId);
        assertThat(created.getQrToken()).startsWith("JCH_");
        assertThat(created.getStatus()).isEqualTo(QrStatus.ACTIVE);
        verify(qrRepository, times(1)).saveAndFlush(any());
    }

    @Test
    void getOrCreateReturnsExistingWithoutInsertingAgain() {
        BusinessQr existing = BusinessQr.builder().businessId(businessId).qrToken("JCH_ABC123").status(QrStatus.ACTIVE).build();
        when(qrRepository.findByBusinessId(businessId)).thenReturn(Optional.of(existing));

        BusinessQr result = qrService.getOrCreate(businessId);

        assertThat(result.getQrToken()).isEqualTo("JCH_ABC123");
        verify(qrRepository, never()).saveAndFlush(any());
    }

    @Test
    void resolvePublicReturnsBusinessIdAndCurrentSlug() {
        BusinessQr qr = BusinessQr.builder().businessId(businessId).qrToken("JCH_XYZ").status(QrStatus.ACTIVE).build();
        when(qrRepository.findByQrTokenAndStatus("JCH_XYZ", QrStatus.ACTIVE)).thenReturn(Optional.of(qr));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business(businessId, ownerUserId, "jj-restaurant", false)));

        QrService.QrResolveResponse res = qrService.resolvePublic("JCH_XYZ");

        assertThat(res.businessId()).isEqualTo(businessId);
        assertThat(res.slug()).isEqualTo("jj-restaurant");
    }

    @Test
    void unknownTokenIs404() {
        when(qrRepository.findByQrTokenAndStatus("JCH_GHOST", QrStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> qrService.resolvePublic("JCH_GHOST"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void inactiveTokenIs404() {
        // findByQrTokenAndStatus(..., ACTIVE) naturally returns empty for an INACTIVE row
        when(qrRepository.findByQrTokenAndStatus("JCH_OFF", QrStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> qrService.resolvePublic("JCH_OFF"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void softDeletedBusinessIs404EvenWithAnActiveToken() {
        BusinessQr qr = BusinessQr.builder().businessId(businessId).qrToken("JCH_DEL").status(QrStatus.ACTIVE).build();
        when(qrRepository.findByQrTokenAndStatus("JCH_DEL", QrStatus.ACTIVE)).thenReturn(Optional.of(qr));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business(businessId, ownerUserId, "jj-restaurant", true)));

        assertThatThrownBy(() -> qrService.resolvePublic("JCH_DEL"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anotherOwnerCannotAccessThisBusinessQr() {
        UUID otherOwnerId = UUID.randomUUID();
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business(businessId, ownerUserId, "jj-restaurant", false)));

        assertThatThrownBy(() -> qrService.getOrCreateForOwner(otherOwnerId, businessId))
                .isInstanceOf(ForbiddenException.class);
        verify(qrRepository, never()).findByBusinessId(any());
    }

    @Test
    void slugChangeIsReflectedOnTheNextResolveWithoutRegeneratingTheQr() {
        BusinessQr qr = BusinessQr.builder().businessId(businessId).qrToken("JCH_STAB").status(QrStatus.ACTIVE).build();
        when(qrRepository.findByQrTokenAndStatus("JCH_STAB", QrStatus.ACTIVE)).thenReturn(Optional.of(qr));
        when(businessRepository.findById(businessId))
                .thenReturn(Optional.of(business(businessId, ownerUserId, "jj-restaurant", false)))
                .thenReturn(Optional.of(business(businessId, ownerUserId, "jj-food-house", false)));

        QrService.QrResolveResponse before = qrService.resolvePublic("JCH_STAB");
        QrService.QrResolveResponse after = qrService.resolvePublic("JCH_STAB");

        assertThat(before.slug()).isEqualTo("jj-restaurant");
        assertThat(after.slug()).isEqualTo("jj-food-house");
        assertThat(after.businessId()).isEqualTo(before.businessId());
    }

    @Test
    void generatedTokenIsUrlSafeAndPrefixed() {
        when(qrRepository.findByBusinessId(businessId)).thenReturn(Optional.empty());
        when(qrRepository.existsByQrToken(any())).thenReturn(false);
        ArgumentCaptor<BusinessQr> captor = ArgumentCaptor.forClass(BusinessQr.class);
        when(qrRepository.saveAndFlush(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        qrService.getOrCreate(businessId);

        String token = captor.getValue().getQrToken();
        assertThat(token).matches("JCH_[A-Za-z0-9]{12}");
    }
}

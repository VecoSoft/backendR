package com.bdreview.platform.qr;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.business.Area;
import com.bdreview.platform.business.AreaRepository;
import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.business.Category;
import com.bdreview.platform.business.CategoryRepository;
import com.bdreview.platform.business.City;
import com.bdreview.platform.business.CityRepository;
import com.bdreview.platform.business.PriceTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the DB-level guarantee behind req 5 of the Business QR V1 spec —
 * "never create multiple active QR records for the same business" — via the
 * {@code UNIQUE(business_id)} constraint (V30) plus {@link QrService}'s
 * catch-and-retry, not just an application-level check. Runs against the real
 * local dev Postgres (no Testcontainers in this repo) and cleans up every row
 * it creates in {@link #cleanUp()}.
 */
@SpringBootTest
class BusinessQrConcurrencyTest {

    @Autowired CategoryRepository categoryRepository;
    @Autowired CityRepository cityRepository;
    @Autowired AreaRepository areaRepository;
    @Autowired BusinessRepository businessRepository;
    @Autowired UserRepository userRepository;
    @Autowired BusinessQrRepository qrRepository;
    @Autowired QrService qrService;

    UUID businessId;
    UUID ownerUserId;

    @BeforeEach
    void setUp() {
        Category category = categoryRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No category seeded"));
        City city = cityRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No city seeded"));
        Area area = areaRepository.findByCityId(city.getId()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No area seeded"));

        String nanos = String.valueOf(System.nanoTime());
        String runSuffix = nanos.substring(nanos.length() - 7);

        User owner = userRepository.save(User.builder()
                .phoneNumber("+88019" + runSuffix)
                .role(UserRole.BUSINESS_OWNER)
                .build());
        ownerUserId = owner.getId();

        Business business = businessRepository.save(Business.builder()
                .ownerUserId(ownerUserId)
                .name("IT QR Business")
                .slug("it-qr-business-" + UUID.randomUUID())
                .category(category)
                .city(city)
                .area(area)
                .contactNumber("+88015" + runSuffix)
                .location(point(23.75, 90.38))
                .priceTier(PriceTier.MODERATE)
                .build());
        businessId = business.getId();
    }

    private static Point point(double latitude, double longitude) {
        GeometryFactory factory = new GeometryFactory(new PrecisionModel(), 4326);
        Point p = factory.createPoint(new Coordinate(longitude, latitude));
        p.setSRID(4326);
        return p;
    }

    @AfterEach
    void cleanUp() {
        qrRepository.findByBusinessId(businessId).ifPresent(qr -> qrRepository.deleteById(qr.getId()));
        businessRepository.deleteById(businessId);
        userRepository.deleteById(ownerUserId);
    }

    @Test
    void onlyOneQrRowIsCreatedForTwoSimultaneousFirstRequests() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Callable<BusinessQr> attempt = () -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            return qrService.getOrCreate(businessId);
        };

        Future<BusinessQr> f1 = pool.submit(attempt);
        Future<BusinessQr> f2 = pool.submit(attempt);
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        BusinessQr r1 = f1.get(10, TimeUnit.SECONDS);
        BusinessQr r2 = f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(r1.getQrToken()).isEqualTo(r2.getQrToken());

        List<BusinessQr> all = qrRepository.findAll().stream()
                .filter(q -> q.getBusinessId().equals(businessId))
                .toList();
        assertThat(all).hasSize(1);
    }
}

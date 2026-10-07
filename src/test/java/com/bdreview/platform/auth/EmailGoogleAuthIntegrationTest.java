package com.bdreview.platform.auth;

import com.bdreview.platform.email.EmailProvider;
import com.bdreview.platform.email.OutgoingEmail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * V70 sign-in end to end through the HTTP layer: Google Sign-In (token checks and account linking),
 * e-mail sign-up with code verification and its limits, password-login lockout, forgot/reset
 * password, and ordering without a phone number (the business reaches the customer by chat).
 * Google's key check is replaced by a mock decoder; every claim check above it runs for real.
 * E-mails are captured instead of sent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/bd_review_it",
        "app.admin.bootstrap-enabled=false",
        "app.auth.google.client-ids=web-it.apps.googleusercontent.com,android-it.apps.googleusercontent.com",
        "app.storage.local-dir=${java.io.tmpdir}/bd-review-it-uploads"
})
class EmailGoogleAuthIntegrationTest {

    private static final String WEB_CLIENT = "web-it.apps.googleusercontent.com";
    private static final String PASSWORD = "Mango-river-42";
    private static final ConcurrentLinkedQueue<OutgoingEmail> SENT = new ConcurrentLinkedQueue<>();
    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    @TestConfiguration
    static class CapturingEmail {
        @Bean
        @Primary
        EmailProvider capturingEmailProvider() {
            return new EmailProvider() {
                @Override
                public void send(OutgoingEmail email) {
                    SENT.add(email);
                }

                @Override
                public String name() {
                    return "capture";
                }
            };
        }
    }

    @MockitoBean(name = "googleIdTokenDecoder")
    JwtDecoder googleDecoder;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    @BeforeEach
    void setUp() {
        SENT.clear();
        jdbc.update("DELETE FROM platform_setting");
        // The IT database keeps rows between runs: start each test without earlier codes (per-IP caps).
        jdbc.update("DELETE FROM auth_email_code");
        doThrow(new JwtException("unknown token")).when(googleDecoder).decode(anyString());
    }

    // =================================================================================== Google

    @Test
    void googleSignInCreatesAVerifiedAccount() throws Exception {
        String sub = "g-" + suffix();
        String email = "new-" + suffix() + "@gmail.com";
        googleToken("tok-new", sub, email, true, WEB_CLIENT, Instant.now().plusSeconds(600));

        MvcResult r = call(HttpMethod.POST, "/api/v1/auth/google", null, Map.of("idToken", "tok-new"));
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        assertThat(json(r).get("accessToken").asText()).isNotBlank();

        User u = userRepository.findByGoogleSub(sub).orElseThrow();
        assertThat(u.getEmail()).isEqualTo(email);
        assertThat(u.getEmailVerifiedAt()).isNotNull();
        assertThat(u.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(u.getPasswordHash()).isNull();
        assertThat(u.getPhoneNumber()).isNull();
        assertThat(u.getName()).isEqualTo("Google Tester");
        assertThat(u.getProfilePhotoUrl()).isEqualTo("https://lh3.googleusercontent.com/a/photo");
    }

    @Test
    void googleTokensWithWrongAudienceIssuerExpiryOrUnverifiedEmailAreRefused() throws Exception {
        googleToken("tok-aud", "g-aud", "a@gmail.com", true, "someone-else.apps.googleusercontent.com", Instant.now().plusSeconds(600));
        assertCode(call(HttpMethod.POST, "/api/v1/auth/google", null, Map.of("idToken", "tok-aud")), 401, "INVALID_GOOGLE_TOKEN");

        googleToken("tok-exp", "g-exp", "b@gmail.com", true, WEB_CLIENT, Instant.now().minusSeconds(5));
        assertCode(call(HttpMethod.POST, "/api/v1/auth/google", null, Map.of("idToken", "tok-exp")), 401, "INVALID_GOOGLE_TOKEN");

        googleToken("tok-unv", "g-unv", "c@gmail.com", false, WEB_CLIENT, Instant.now().plusSeconds(600));
        assertCode(call(HttpMethod.POST, "/api/v1/auth/google", null, Map.of("idToken", "tok-unv")), 401, "GOOGLE_EMAIL_NOT_VERIFIED");

        doReturn(Jwt.withTokenValue("tok-iss").header("alg", "RS256")
                .subject("g-iss").issuer("https://evil.example.com").audience(List.of(WEB_CLIENT))
                .issuedAt(Instant.now().minusSeconds(10)).expiresAt(Instant.now().plusSeconds(600))
                .claim("email", "d@gmail.com").claim("email_verified", true).build()).when(googleDecoder).decode("tok-iss");
        assertCode(call(HttpMethod.POST, "/api/v1/auth/google", null, Map.of("idToken", "tok-iss")), 401, "INVALID_GOOGLE_TOKEN");

        assertCode(call(HttpMethod.POST, "/api/v1/auth/google", null, Map.of("idToken", "garbage")), 401, "INVALID_GOOGLE_TOKEN");
        assertThat(userRepository.findByGoogleSub("g-aud")).isEmpty();
        assertThat(userRepository.findByGoogleSub("g-exp")).isEmpty();
        assertThat(userRepository.findByGoogleSub("g-unv")).isEmpty();
    }

    @Test
    void googleLinksToTheExistingAccountWithThatEmail() throws Exception {
        User existing = passwordUser("link-" + suffix() + "@example.com");
        googleToken("tok-link", "g-link-" + suffix(), existing.getEmail(), true, "android-it.apps.googleusercontent.com",
                Instant.now().plusSeconds(600));

        MvcResult r = call(HttpMethod.POST, "/api/v1/auth/google", null, Map.of("idToken", "tok-link"));
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        assertThat(jwtService.parse(json(r).get("accessToken").asText()).getSubject()).isEqualTo(existing.getId().toString());
        User linked = userRepository.findById(existing.getId()).orElseThrow();
        assertThat(linked.getGoogleSub()).startsWith("g-link-");
        assertThat(linked.getAuthProvider()).isEqualTo(AuthProvider.BOTH);
        // the password still works too
        assertThat(login(existing.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void googleTakesOverAnUnverifiedSignupAndDropsItsPassword() throws Exception {
        String email = "squat-" + suffix() + "@example.com";
        register("Squatter", email, PASSWORD);
        googleToken("tok-owner", "g-owner-" + suffix(), email, true, WEB_CLIENT, Instant.now().plusSeconds(600));

        assertThat(call(HttpMethod.POST, "/api/v1/auth/google", null, Map.of("idToken", "tok-owner")).getResponse().getStatus()).isEqualTo(200);
        User u = userRepository.findByEmailNormalized(email).orElseThrow();
        assertThat(u.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(u.getPasswordHash()).isNull();
        // whoever registered the address first can't get in with the password they chose
        assertCode(login(email, PASSWORD), 400, "USE_GOOGLE_SIGN_IN");
    }

    // =================================================================================== sign-up

    @Test
    void signUpNeedsTheEmailedCodeBeforeLogin() throws Exception {
        String email = "Signup-" + suffix() + "@Example.com ";
        MvcResult reg = register("Rahim Uddin", email, PASSWORD);
        assertThat(reg.getResponse().getStatus()).as(body(reg)).isEqualTo(202);
        String normalized = email.trim().toLowerCase();
        assertThat(json(reg).get("email").asText()).isEqualTo(normalized);

        User u = userRepository.findByEmailNormalized(normalized).orElseThrow();
        assertThat(u.getAccountStatus()).isEqualTo(AccountStatus.EMAIL_UNVERIFIED);
        assertThat(u.getPasswordHash()).startsWith("$2a$12$");

        MvcResult blocked = login(normalized, PASSWORD);
        assertCode(blocked, 403, "EMAIL_NOT_VERIFIED");
        assertThat(json(blocked).get("canResend").asBoolean()).isTrue();

        MvcResult wrong = call(HttpMethod.POST, "/api/v1/auth/verify-email", null, Map.of("email", normalized, "code", "000000"));
        assertCode(wrong, 400, "CODE_INVALID");
        assertThat(json(wrong).get("attemptsLeft").asInt()).isEqualTo(4);

        String code = codeFrom(waitForEmail(normalized, "verification code"));
        MvcResult ok = call(HttpMethod.POST, "/api/v1/auth/verify-email", null, Map.of("email", normalized, "code", code));
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(json(ok).get("accessToken").asText()).isNotBlank();
        assertThat(userRepository.findById(u.getId()).orElseThrow().getEmailVerifiedAt()).isNotNull();
        assertThat(login(normalized, PASSWORD).getResponse().getStatus()).isEqualTo(200);
        // a used code can't be used again
        assertCode(call(HttpMethod.POST, "/api/v1/auth/verify-email", null, Map.of("email", normalized, "code", code)), 400, "CODE_EXPIRED");
    }

    @Test
    void fiveWrongCodesKillTheCodeAndExpiredCodesAreRefused() throws Exception {
        String email = "limits-" + suffix() + "@example.com";
        register("Limits", email, PASSWORD);
        String code = codeFrom(waitForEmail(email, "verification code"));
        String wrong = code.equals("111111") ? "222222" : "111111";
        for (int i = 0; i < 4; i++) {
            assertCode(call(HttpMethod.POST, "/api/v1/auth/verify-email", null, Map.of("email", email, "code", wrong)), 400, "CODE_INVALID");
        }
        assertCode(call(HttpMethod.POST, "/api/v1/auth/verify-email", null, Map.of("email", email, "code", wrong)), 400, "CODE_TOO_MANY_ATTEMPTS");
        // even the right code is dead now
        assertCode(call(HttpMethod.POST, "/api/v1/auth/verify-email", null, Map.of("email", email, "code", code)), 400, "CODE_TOO_MANY_ATTEMPTS");

        // a fresh code (after the resend cooldown), then expired
        jdbc.update("UPDATE auth_email_code SET created_at = created_at - interval '2 minutes' WHERE email = ?", email);
        MvcResult resend = call(HttpMethod.POST, "/api/v1/auth/resend-verification", null, Map.of("email", email));
        assertThat(resend.getResponse().getStatus()).isEqualTo(202);
        String fresh = codeFrom(waitForEmail(email, "verification code", 2));
        jdbc.update("UPDATE auth_email_code SET expires_at = now() - interval '1 minute' WHERE email = ? AND consumed_at IS NULL", email);
        assertCode(call(HttpMethod.POST, "/api/v1/auth/verify-email", null, Map.of("email", email, "code", fresh)), 400, "CODE_EXPIRED");
    }

    @Test
    void resendWithinTheCooldownSendsNothingNew() throws Exception {
        String email = "cool-" + suffix() + "@example.com";
        register("Cooldown", email, PASSWORD);
        waitForEmail(email, "verification code");
        MvcResult again = call(HttpMethod.POST, "/api/v1/auth/resend-verification", null, Map.of("email", email));
        assertThat(again.getResponse().getStatus()).isEqualTo(202);
        assertThat(json(again).get("resendAfterSeconds").asLong()).isPositive();
        Thread.sleep(500);
        assertThat(SENT.stream().filter(m -> m.to().equals(email)).count()).isEqualTo(1);
    }

    @Test
    void signUpRulesHaveTheirOwnCodes() throws Exception {
        String ok = "rules-" + suffix() + "@example.com";
        assertCode(register("A", "not-an-email", PASSWORD), 400, "INVALID_EMAIL");
        assertCode(register("A", "x" + suffix() + "@mailinator.com", PASSWORD), 400, "DISPOSABLE_EMAIL");
        assertCode(register("A", "y" + suffix() + "@inbox.mailinator.com", PASSWORD), 400, "DISPOSABLE_EMAIL");
        assertCode(register("A", ok, "abc12"), 400, "PASSWORD_TOO_SHORT");
        assertCode(register("A", ok, "abcdefghij"), 400, "PASSWORD_NEEDS_LETTER_AND_NUMBER");
        assertCode(register("A", ok, "12345678901"), 400, "PASSWORD_NEEDS_LETTER_AND_NUMBER");
        assertCode(register("A", ok, "Password123"), 400, "PASSWORD_TOO_COMMON");
        assertCode(call(HttpMethod.POST, "/api/v1/auth/register", null,
                Map.of("name", "A", "email", ok, "password", PASSWORD, "confirmPassword", PASSWORD + "x")), 400, "PASSWORD_MISMATCH");
        assertCode(register(" ", ok, PASSWORD), 400, "NAME_REQUIRED");
        assertThat(userRepository.findByEmailNormalized(ok)).isEmpty();

        User taken = passwordUser("taken-" + suffix() + "@example.com");
        assertCode(register("B", taken.getEmail().toUpperCase(), PASSWORD), 409, "EMAIL_TAKEN");
    }

    // =================================================================================== login

    @Test
    void fiveWrongPasswordsLockTheAccountFor15Minutes() throws Exception {
        User u = passwordUser("lock-" + suffix() + "@example.com");
        MvcResult unknown = login("nobody-" + suffix() + "@example.com", PASSWORD);
        assertCode(unknown, 401, "INVALID_CREDENTIALS");
        for (int i = 0; i < 4; i++) {
            MvcResult wrong = login(u.getEmail(), "Wrong-pass-" + i);
            assertCode(wrong, 401, "INVALID_CREDENTIALS");
            // same message whether the e-mail exists or not
            assertThat(json(wrong).get("message").asText()).isEqualTo(json(unknown).get("message").asText()).isEqualTo("Email or password is incorrect");
        }
        MvcResult fifth = login(u.getEmail(), "Wrong-pass-5");
        assertCode(fifth, 429, "ACCOUNT_LOCKED");
        assertThat(json(fifth).get("retryAfterSeconds").asLong()).isBetween(14 * 60L, 15 * 60L);
        // the right password doesn't help while locked
        assertCode(login(u.getEmail(), PASSWORD), 429, "ACCOUNT_LOCKED");

        User locked = userRepository.findById(u.getId()).orElseThrow();
        assertThat(locked.getLoginLockedUntil()).isAfter(Instant.now().plus(Duration.ofMinutes(14)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_login_event WHERE user_id = ? AND outcome = 'FAILED'", Long.class, u.getId())).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_login_event WHERE user_id = ? AND outcome = 'LOCKED'", Long.class, u.getId())).isEqualTo(2);

        // once the lock has passed, the right password works and the counter starts over
        jdbc.update("UPDATE app_user SET login_locked_until = now() - interval '1 second' WHERE id = ?", u.getId());
        assertThat(login(u.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(userRepository.findById(u.getId()).orElseThrow().getFailedLoginCount()).isZero();
    }

    @Test
    void aGoogleOnlyAccountIsToldToUseGoogle() throws Exception {
        String email = "gonly-" + suffix() + "@gmail.com";
        userRepository.save(User.builder().role(UserRole.CONSUMER).name("G only").email(email).emailVerifiedAt(Instant.now())
                .googleSub("g-only-" + suffix()).authProvider(AuthProvider.GOOGLE).build());
        assertCode(login(email, PASSWORD), 400, "USE_GOOGLE_SIGN_IN");
    }

    // =================================================================================== forgot / reset

    @Test
    void forgotPasswordAnswersTheSameForUnknownAddresses() throws Exception {
        User u = passwordUser("forgot-" + suffix() + "@example.com");
        MvcResult known = call(HttpMethod.POST, "/api/v1/auth/forgot-password", null, Map.of("email", u.getEmail()));
        MvcResult unknown = call(HttpMethod.POST, "/api/v1/auth/forgot-password", null, Map.of("email", "ghost-" + suffix() + "@example.com"));
        assertThat(known.getResponse().getStatus()).isEqualTo(202).isEqualTo(unknown.getResponse().getStatus());
        assertThat(body(known)).isEqualTo(body(unknown));
        waitForEmail(u.getEmail(), "password reset code");
        Thread.sleep(300);
        assertThat(SENT).allMatch(m -> m.to().equals(u.getEmail()));
    }

    @Test
    void resetPasswordSignsOutEverywhereAndSendsANotice() throws Exception {
        User u = passwordUser("reset-" + suffix() + "@example.com");
        String refresh = json(login(u.getEmail(), PASSWORD)).get("refreshToken").asText();

        call(HttpMethod.POST, "/api/v1/auth/forgot-password", null, Map.of("email", u.getEmail()));
        String code = codeFrom(waitForEmail(u.getEmail(), "password reset code"));
        String newPassword = "Kathal-tree-77";
        assertCode(call(HttpMethod.POST, "/api/v1/auth/reset-password", null,
                Map.of("email", u.getEmail(), "code", code, "password", newPassword, "confirmPassword", "different-9")), 400, "PASSWORD_MISMATCH");
        MvcResult reset = call(HttpMethod.POST, "/api/v1/auth/reset-password", null,
                Map.of("email", u.getEmail(), "code", code, "password", newPassword, "confirmPassword", newPassword));
        assertThat(reset.getResponse().getStatus()).as(body(reset)).isEqualTo(200);

        assertThat(call(HttpMethod.POST, "/api/v1/auth/refresh", null, Map.of("refreshToken", refresh)).getResponse().getStatus()).isEqualTo(403);
        assertCode(login(u.getEmail(), PASSWORD), 401, "INVALID_CREDENTIALS");
        assertThat(login(u.getEmail(), newPassword).getResponse().getStatus()).isEqualTo(200);
        assertThat(waitForEmail(u.getEmail(), "password was changed").text()).contains(u.getEmail());
    }

    // =================================================================================== no phone: orders, chat, legacy accounts

    @Test
    void anOrderNeedsNoPhoneAndTheOwnerReachesTheCustomerByChat() throws Exception {
        Shop shop = shop();
        User customer = passwordUser("buyer-" + suffix() + "@example.com");
        String customerToken = jwtService.generateAccessToken(customer.getId(), UserRole.CONSUMER);

        MvcResult placed = call(HttpMethod.POST, "/api/v1/businesses/" + shop.businessId + "/orders", customerToken, orderBody(shop));
        assertThat(placed.getResponse().getStatus()).as(body(placed)).isEqualTo(200);
        JsonNode order = json(placed);
        assertThat(order.has("customerPhone")).isFalse();
        String orderId = order.get("id").asText();
        assertThat(jdbc.queryForObject("SELECT customer_phone_snapshot FROM business_order WHERE id = ?::uuid", String.class, orderId)).isNull();

        // the owner's view of the order has no phone either
        assertThat(json(call(HttpMethod.GET, "/api/v1/orders/" + orderId, shop.ownerToken, null)).has("customerPhone")).isFalse();

        // "Message customer": opens (once) a thread the owner can write in
        MvcResult chat = call(HttpMethod.POST, "/api/v1/orders/" + orderId + "/customer-chat", shop.ownerToken, null);
        assertThat(chat.getResponse().getStatus()).as(body(chat)).isEqualTo(200);
        String threadId = json(chat).get("threadId").asText();
        assertThat(json(call(HttpMethod.POST, "/api/v1/orders/" + orderId + "/customer-chat", shop.ownerToken, null)).get("threadId").asText())
                .isEqualTo(threadId);
        assertThat(call(HttpMethod.POST, "/api/v1/messages/threads/" + threadId + "/reply", shop.ownerToken,
                Map.of("content", "Your kacchi will be ready at 8")).getResponse().getStatus()).isEqualTo(200);
        assertThat(body(call(HttpMethod.GET, "/api/v1/messages/threads/mine", customerToken, null))).contains(threadId);
        // nobody else can open it
        assertThat(call(HttpMethod.POST, "/api/v1/orders/" + orderId + "/customer-chat", customerToken, null).getResponse().getStatus())
                .isEqualTo(403);

        // the customer is notified in the app when the order moves on
        assertThat(call(HttpMethod.PATCH, "/api/v1/orders/" + orderId + "/status", shop.ownerToken, Map.of("status", "ACCEPTED"))
                .getResponse().getStatus()).isEqualTo(200);
        // (sent @Async after the status change)
        long notified = 0;
        for (int i = 0; i < 50 && notified == 0; i++) {
            Thread.sleep(100);
            notified = jdbc.queryForObject("SELECT count(*) FROM notification WHERE recipient_user_id = ? AND channel = 'IN_APP'",
                    Long.class, customer.getId());
        }
        assertThat(notified).isPositive();
    }

    @Test
    void aPhoneOnlyAccountAddsAnEmailBeforeOrdering() throws Exception {
        Shop shop = shop();
        User legacy = userRepository.save(User.builder().role(UserRole.CONSUMER).name("Old phone user")
                .phoneNumber("+8801" + suffix().substring(0, 8) + (int) (Math.random() * 10))
                .otpVerified(true).passwordHash(passwordEncoder.encode("old-pass-1")).build());
        String token = jwtService.generateAccessToken(legacy.getId(), UserRole.CONSUMER);
        assertThat(json(call(HttpMethod.GET, "/api/v1/users/me", token, null)).get("needsEmail").asBoolean()).isTrue();
        assertCode(call(HttpMethod.POST, "/api/v1/businesses/" + shop.businessId + "/orders", token, orderBody(shop)), 403,
                "EMAIL_VERIFICATION_REQUIRED");

        String email = "legacy-" + suffix() + "@example.com";
        MvcResult add = call(HttpMethod.POST, "/api/v1/users/me/email", token,
                Map.of("email", email, "password", PASSWORD, "confirmPassword", PASSWORD));
        assertThat(add.getResponse().getStatus()).as(body(add)).isEqualTo(202);
        String code = codeFrom(waitForEmail(email, "verification code"));
        MvcResult verified = call(HttpMethod.POST, "/api/v1/users/me/email/verify", token, Map.of("email", email, "code", code));
        assertThat(verified.getResponse().getStatus()).as(body(verified)).isEqualTo(200);
        assertThat(json(verified).get("emailVerified").asBoolean()).isTrue();
        assertThat(json(verified).get("needsEmail").asBoolean()).isFalse();
        assertThat(json(verified).has("phoneNumber")).isFalse();

        assertThat(call(HttpMethod.POST, "/api/v1/businesses/" + shop.businessId + "/orders", token, orderBody(shop))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anAdminCanAttachAnEmailWhichForgotPasswordThenVerifies() throws Exception {
        User admin = userRepository.save(User.builder().role(UserRole.ADMIN).name("Admin IT")
                .phoneNumber("+8801" + suffix().substring(0, 8) + (int) (Math.random() * 10))
                .otpVerified(true).passwordHash(passwordEncoder.encode(PASSWORD)).build());
        User legacy = userRepository.save(User.builder().role(UserRole.CONSUMER).name("Locked-out user")
                .phoneNumber("+8801" + suffix().substring(0, 8) + (int) (Math.random() * 10)).otpVerified(true).build());
        String email = "attached-" + suffix() + "@example.com";

        mvc.perform(post("/admin/users/" + legacy.getId() + "/email").with(user(admin.getId().toString()).roles("ADMIN")).with(csrf())
                .param("email", email).param("reason", "User lost their phone; confirmed by support call"));
        User attached = userRepository.findById(legacy.getId()).orElseThrow();
        assertThat(attached.getEmail()).isEqualTo(email);
        assertThat(attached.getEmailVerifiedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'EMAIL_ATTACHED'",
                Long.class, legacy.getId())).isEqualTo(1);

        call(HttpMethod.POST, "/api/v1/auth/forgot-password", null, Map.of("email", email));
        String code = codeFrom(waitForEmail(email, "password reset code"));
        assertThat(call(HttpMethod.POST, "/api/v1/auth/reset-password", null,
                Map.of("email", email, "code", code, "password", PASSWORD, "confirmPassword", PASSWORD)).getResponse().getStatus()).isEqualTo(200);
        User done = userRepository.findById(legacy.getId()).orElseThrow();
        assertThat(done.getEmailVerifiedAt()).isNotNull();
        assertThat(done.getAuthProvider()).isEqualTo(AuthProvider.PASSWORD);
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void phoneLoginIsGone() throws Exception {
        // The old phone + OTP sign-in endpoints no longer exist (no token issued, nothing served).
        assertThat(call(HttpMethod.POST, "/api/v1/otp/request", null, Map.of("phoneNumber", "01712345678")).getResponse().getStatus()).isIn(401, 404);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/phone/login", null, Map.of("phoneNumber", "01712345678", "password", "x"))
                .getResponse().getStatus()).isEqualTo(404);
        assertCode(call(HttpMethod.POST, "/api/v1/auth/login", null, Map.of("phoneNumber", "01712345678", "password", "x")), 401,
                "INVALID_CREDENTIALS");
    }

    // =================================================================================== helpers

    private record Shop(UUID businessId, UUID menuItemId, String ownerToken) {
    }

    /** A restaurant taking pickup orders, with one priced menu item. */
    private Shop shop() {
        User owner = userRepository.save(User.builder().role(UserRole.BUSINESS_OWNER).name("Owner IT")
                .email("owner-" + suffix() + "@example.com").emailVerifiedAt(Instant.now()).authProvider(AuthProvider.PASSWORD).build());
        UUID categoryId = UUID.randomUUID();
        jdbc.update("INSERT INTO category (id, name, kind) VALUES (?, ?, 'RESTAURANT')", categoryId, "Food V70 " + suffix());
        UUID cityId = UUID.randomUUID();
        jdbc.update("INSERT INTO city (id, name) VALUES (?, ?)", cityId, "City V70 " + suffix());
        UUID areaId = UUID.randomUUID();
        jdbc.update("INSERT INTO area (id, city_id, name) VALUES (?, ?, ?)", areaId, cityId, "Area V70 " + suffix());
        UUID businessId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO business (id, owner_user_id, name, slug, category_id, city_id, area_id, contact_number, location, verified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(90.37, 23.80), 4326), false)
                """, businessId, owner.getId(), "V70 Kitchen " + suffix(), "v70-" + businessId, categoryId, cityId, areaId,
                "+88017" + suffix());
        jdbc.update("""
                INSERT INTO business_commerce_settings (business_id, mode, ordering_enabled, pickup_enabled, accepting_orders,
                    payment_pay_at_business) VALUES (?, 'DIRECT_ORDER', true, true, true, true)
                """, businessId);
        UUID menuItemId = UUID.randomUUID();
        jdbc.update("INSERT INTO business_menu_item (id, business_id, name, price, available) VALUES (?, ?, 'Kacchi', 350, true)",
                menuItemId, businessId);
        return new Shop(businessId, menuItemId, jwtService.generateAccessToken(owner.getId(), UserRole.BUSINESS_OWNER));
    }

    private static Map<String, Object> orderBody(Shop shop) {
        return Map.of("fulfillmentType", "PICKUP", "paymentMethod", "PAY_AT_BUSINESS", "customerName", "Rahim",
                "items", List.of(Map.of("menuItemId", shop.menuItemId.toString(), "quantity", 2)));
    }

    private User passwordUser(String email) {
        return userRepository.save(User.builder().role(UserRole.CONSUMER).name("Password user").email(email)
                .emailVerifiedAt(Instant.now()).authProvider(AuthProvider.PASSWORD)
                .passwordHash(passwordEncoder.encode(PASSWORD)).build());
    }

    private void googleToken(String token, String sub, String email, boolean emailVerified, String audience, Instant expiresAt) {
        doReturn(Jwt.withTokenValue(token).header("alg", "RS256")
                .subject(sub).issuer("https://accounts.google.com").audience(List.of(audience))
                .issuedAt(expiresAt.minusSeconds(3600)).expiresAt(expiresAt)
                .claim("email", email).claim("email_verified", emailVerified)
                .claim("name", "Google Tester").claim("picture", "https://lh3.googleusercontent.com/a/photo")
                .build()).when(googleDecoder).decode(token);
    }

    private MvcResult register(String name, String email, String password) throws Exception {
        return call(HttpMethod.POST, "/api/v1/auth/register", null,
                Map.of("name", name, "email", email, "password", password, "confirmPassword", password));
    }

    private MvcResult login(String email, String password) throws Exception {
        return call(HttpMethod.POST, "/api/v1/auth/login", null, Map.of("email", email, "password", password));
    }

    private OutgoingEmail waitForEmail(String to, String subjectContains) throws InterruptedException {
        return waitForEmail(to, subjectContains, 1);
    }

    /** Waits (e-mail goes out after commit on a background thread) for the n-th matching message. */
    private OutgoingEmail waitForEmail(String to, String subjectContains, int nth) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            List<OutgoingEmail> matches = new ArrayList<>(SENT.stream()
                    .filter(m -> m.to().equals(to) && m.subject().contains(subjectContains)).toList());
            if (matches.size() >= nth) {
                return matches.get(nth - 1);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No e-mail \"" + subjectContains + "\" to " + to + "; sent: " + SENT);
    }

    private static String codeFrom(OutgoingEmail email) {
        Matcher m = CODE.matcher(email.text());
        assertThat(m.find()).as(email.text()).isTrue();
        return m.group(1);
    }

    private MvcResult call(HttpMethod method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder b = request(method, url).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            b.content(objectMapper.writeValueAsString(body));
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(b).andReturn();
    }

    private void assertCode(MvcResult r, int status, String code) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        assertThat(json(r).path("code").asText()).as(body(r)).isEqualTo(code);
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private static String suffix() {
        String n = String.valueOf(System.nanoTime());
        return n.substring(n.length() - 8) + (int) (Math.random() * 1000);
    }
}

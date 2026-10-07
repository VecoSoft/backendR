package com.bdreview.platform.auth;

import com.bdreview.platform.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * App sign-in (V70): Google Sign-In and e-mail + password. Feature flags (FeatureGateInterceptor):
 * GOOGLE_LOGIN gates /google, PASSWORD_LOGIN gates the e-mail endpoints, NEW_SIGNUPS gates /register.
 * The admin panel login (phone + password + TOTP) is not here (admin.config).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/google")
    public ResponseEntity<TokenPairDto> google(@Valid @RequestBody AuthRequests.GoogleLoginRequest request) {
        return ResponseEntity.ok(authService.google(request.idToken(), request.context()));
    }

    /** E-mail sign-up: 202 + the address to show on the code screen; nothing is signed in yet. */
    @PostMapping("/register")
    public ResponseEntity<AuthService.VerificationPending> register(@Valid @RequestBody RegisterRequestDto request,
                                                                    HttpServletRequest http) {
        return ResponseEntity.accepted().body(authService.register(request.name(), request.email(), request.password(),
                request.confirmPassword(), request.language(), http.getRemoteAddr()));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<TokenPairDto> verifyEmail(@Valid @RequestBody AuthRequests.EmailCodeRequest request) {
        return ResponseEntity.ok(authService.verifyEmail(request.email(), request.code()));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<AuthService.VerificationPending> resendVerification(
            @Valid @RequestBody AuthRequests.EmailOnlyRequest request, HttpServletRequest http) {
        return ResponseEntity.accepted().body(authService.resendVerification(request.email(), http.getRemoteAddr()));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenPairDto> login(@Valid @RequestBody LoginRequestDto request, HttpServletRequest http) {
        return ResponseEntity.ok(authService.login(request.email(), request.password(), request.context(), http.getRemoteAddr()));
    }

    /** Always 202 with the same body, whether or not the address has an account. */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody AuthRequests.EmailOnlyRequest request,
                                                              HttpServletRequest http) {
        authService.forgotPassword(request.email(), http.getRemoteAddr());
        return ResponseEntity.accepted().body(Map.of("status", "CODE_SENT_IF_ACCOUNT_EXISTS"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequestDto request) {
        authService.resetPassword(request.email(), request.code(), request.password(), request.confirmPassword());
        return ResponseEntity.ok(Map.of("status", "PASSWORD_CHANGED"));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenPairDto> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        authService.logout(CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    /** Frictionless switch to the caller's linked counterpart account (see accountlink.AccountLink) — no password re-entry. */
    @PostMapping("/switch-account")
    public ResponseEntity<TokenPairDto> switchAccount() {
        return ResponseEntity.ok(authService.switchAccount(CurrentUser.id()));
    }

    /** Signed-in personal account creates + links its business account and switches into it. */
    @PostMapping("/register-business")
    public ResponseEntity<TokenPairDto> registerBusiness(@Valid @RequestBody RegisterBusinessRequest request) {
        return ResponseEntity.ok(authService.registerBusinessFromConsumer(CurrentUser.id(), request.name()));
    }
}

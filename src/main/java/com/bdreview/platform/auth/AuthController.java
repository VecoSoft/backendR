package com.bdreview.platform.auth;

import com.bdreview.platform.common.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<TokenPairDto> register(@Valid @RequestBody RegisterRequestDto request) {
        return ResponseEntity.ok(
                authService.register(request.phoneNumber(), request.code(), request.password(), request.role(),
                        request.name()));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenPairDto> login(@Valid @RequestBody LoginRequestDto request) {
        return ResponseEntity.ok(authService.login(request.phoneNumber(), request.password(), request.context()));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<TokenPairDto> resetPassword(@Valid @RequestBody ResetPasswordRequestDto request) {
        return ResponseEntity.ok(
                authService.resetPassword(request.phoneNumber(), request.code(), request.newPassword(), request.role()));
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

    /** Logged-in CONSUMER creates+links its BUSINESS_OWNER counterpart in one step, no fresh OTP needed. */
    @PostMapping("/register-business")
    public ResponseEntity<TokenPairDto> registerBusiness(@Valid @RequestBody RegisterBusinessRequest request) {
        return ResponseEntity.ok(
                authService.registerBusinessFromConsumer(CurrentUser.id(), request.password(), request.name()));
    }

    /** Links the caller's account with an independently-registered opposite-role account under the same phone, via OTP proof. */
    @PostMapping("/link-accounts")
    public ResponseEntity<Void> linkAccounts(@Valid @RequestBody LinkAccountsRequest request) {
        authService.linkAccounts(CurrentUser.id(), request.code());
        return ResponseEntity.noContent().build();
    }
}

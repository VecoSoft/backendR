package com.bdreview.platform.auth;

import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.gallery.PreSignedUploadResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    public UserController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<UserProfileDto> me() {
        return ResponseEntity.ok(userService.getProfile(CurrentUser.id()));
    }

    @PutMapping
    public ResponseEntity<UserProfileDto> update(@Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateProfile(
                CurrentUser.id(), request.name(), request.preferredLanguage(), request.profilePhotoUrl()));
    }

    @PostMapping("/photo/upload-url")
    public ResponseEntity<PreSignedUploadResponse> requestPhotoUploadUrl(@RequestParam String filename) {
        return ResponseEntity.ok(userService.requestPhotoUploadUrl(CurrentUser.id(), filename));
    }

    /** Separate upload path from /photo/upload-url above — see UserService#requestCommunityAvatarUploadUrl. */
    @PostMapping("/community-avatar/upload-url")
    public ResponseEntity<PreSignedUploadResponse> requestCommunityAvatarUploadUrl(@RequestParam String filename) {
        return ResponseEntity.ok(userService.requestCommunityAvatarUploadUrl(filename));
    }

    @PutMapping("/community-avatar")
    public ResponseEntity<UserProfileDto> updateCommunityAvatar(@RequestBody UpdateCommunityAvatarRequest request) {
        return ResponseEntity.ok(userService.updateCommunityAvatar(CurrentUser.id(), request.communityAvatarUrl()));
    }

    // ---- V70: phone-only accounts add an e-mail (or link Google) so they can keep signing in ----

    /** Sets the password now and e-mails a code to the new address (202). */
    @PostMapping("/email")
    public ResponseEntity<AuthService.VerificationPending> addEmail(@Valid @RequestBody AuthRequests.AddEmailRequest request,
                                                                   HttpServletRequest http) {
        return ResponseEntity.accepted().body(authService.startAddEmail(CurrentUser.id(), request.email(),
                request.password(), request.confirmPassword(), http.getRemoteAddr()));
    }

    @PostMapping("/email/verify")
    public ResponseEntity<UserProfileDto> verifyAddedEmail(@Valid @RequestBody AuthRequests.EmailCodeRequest request) {
        authService.confirmAddEmail(CurrentUser.id(), request.email(), request.code());
        return ResponseEntity.ok(userService.getProfile(CurrentUser.id()));
    }

    @PostMapping("/google")
    public ResponseEntity<UserProfileDto> linkGoogle(@Valid @RequestBody AuthRequests.LinkGoogleRequest request) {
        authService.linkGoogleToCurrent(CurrentUser.id(), request.idToken());
        return ResponseEntity.ok(userService.getProfile(CurrentUser.id()));
    }
}

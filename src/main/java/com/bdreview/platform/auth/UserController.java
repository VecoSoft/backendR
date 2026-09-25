package com.bdreview.platform.auth;

import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.gallery.PreSignedUploadResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
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
}

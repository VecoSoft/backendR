package com.bdreview.platform.community;

import com.bdreview.platform.common.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Community pseudonymous-identity endpoints — separate from UserController
 * (auth/UserController) which owns the private account profile. See
 * CommunityUsernameService for the uniqueness/format/reserved-word rules.
 */
@RestController
@RequestMapping("/api/v1/community")
public class CommunityProfileController {

    private final CommunityUsernameService usernameService;
    private final CommunityPostService communityPostService;

    public CommunityProfileController(CommunityUsernameService usernameService,
                                       CommunityPostService communityPostService) {
        this.usernameService = usernameService;
        this.communityPostService = communityPostService;
    }

    @GetMapping("/username/available")
    public ResponseEntity<UsernameAvailabilityResponse> checkAvailability(@RequestParam String value) {
        return ResponseEntity.ok(new UsernameAvailabilityResponse(usernameService.isAvailable(value)));
    }

    @GetMapping("/username/suggestion")
    public ResponseEntity<UsernameSuggestionResponse> suggestion() {
        return ResponseEntity.ok(new UsernameSuggestionResponse(usernameService.suggest()));
    }

    @PostMapping("/username")
    public ResponseEntity<SetCommunityUsernameResponse> setUsername(@Valid @RequestBody SetCommunityUsernameRequest request) {
        String username = usernameService.setUsername(CurrentUser.id(), request.username(), request.gender());
        return ResponseEntity.ok(genderResponse(username, CurrentUser.id()));
    }

    /** V59: pick/change the M/F badge or show/hide it. */
    @PutMapping("/gender")
    public ResponseEntity<SetCommunityUsernameResponse> updateGender(@RequestBody UpdateCommunityGenderRequest request) {
        var user = usernameService.updateGender(CurrentUser.id(), request.gender(), request.visible());
        return ResponseEntity.ok(new SetCommunityUsernameResponse(user.getCommunityUsername(), user.getCommunityGender(),
                user.isCommunityGenderVisible()));
    }

    private SetCommunityUsernameResponse genderResponse(String username, java.util.UUID userId) {
        var user = usernameService.currentUser(userId);
        return new SetCommunityUsernameResponse(username, user.getCommunityGender(), user.isCommunityGenderVisible());
    }

    @GetMapping("/profile/{username}")
    public ResponseEntity<CommunityProfileResponse> getProfile(@PathVariable String username) {
        return ResponseEntity.ok(communityPostService.getProfile(username, CurrentUser.idOrNull()));
    }
}

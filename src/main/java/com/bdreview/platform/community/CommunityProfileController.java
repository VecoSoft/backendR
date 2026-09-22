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
        String username = usernameService.setUsername(CurrentUser.id(), request.username());
        return ResponseEntity.ok(new SetCommunityUsernameResponse(username));
    }

    @GetMapping("/profile/{username}")
    public ResponseEntity<CommunityProfileResponse> getProfile(@PathVariable String username) {
        return ResponseEntity.ok(communityPostService.getProfile(username, CurrentUser.idOrNull()));
    }
}

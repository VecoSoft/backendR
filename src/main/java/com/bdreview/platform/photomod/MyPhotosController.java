package com.bdreview.platform.photomod;

import com.bdreview.platform.common.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The uploader's own moderation state (V63): photos still waiting for review and recent
 * rejections, so the app can show "Waiting for review" next to them. Only ever the caller's own.
 */
@RestController
@RequestMapping("/api/v1/photos")
public class MyPhotosController {

    private final PhotoModerationService photoModeration;

    public MyPhotosController(PhotoModerationService photoModeration) {
        this.photoModeration = photoModeration;
    }

    public record MyPhoto(UUID id, PhotoSource source, UUID sourceId, UUID businessId, String url,
                          PhotoStatus status, String label, String reason, Instant createdAt) {
    }

    @GetMapping("/mine")
    public List<MyPhoto> mine() {
        return photoModeration.mine(CurrentUser.id()).stream()
                .map(p -> new MyPhoto(p.getId(), p.getSourceType(), p.getSourceId(), p.getBusinessId(), p.getUrl(),
                        p.getStatus(),
                        p.getStatus() == PhotoStatus.PENDING ? "Waiting for review" : "Not approved",
                        p.getStatus() == PhotoStatus.REJECTED ? p.getReason() : null,
                        p.getCreatedAt()))
                .toList();
    }
}

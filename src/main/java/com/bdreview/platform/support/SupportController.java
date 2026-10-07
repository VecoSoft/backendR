package com.bdreview.platform.support;

import com.bdreview.platform.common.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Help → Contact support (V67): any signed-in user or owner opens and follows their own support requests. */
@RestController
@RequestMapping("/api/v1/support/tickets")
public class SupportController {

    public record OpenRequest(String category, String subject, String message, String screenshotUrl) {
    }

    public record ReplyRequest(String message) {
    }

    private final SupportService support;

    public SupportController(SupportService support) {
        this.support = support;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> open(@RequestBody OpenRequest request) {
        UUID id = support.open(CurrentUser.id(), request.category(), request.subject(), request.message(), request.screenshotUrl());
        return ResponseEntity.ok(support.mineDetail(CurrentUser.id(), id));
    }

    /** Pre-signed upload for the optional screenshot (stored under support/, never public). */
    @PostMapping("/upload-url")
    public com.bdreview.platform.gallery.PreSignedUploadResponse uploadUrl(@RequestParam String filename) {
        return support.screenshotUploadUrl(filename);
    }

    @GetMapping
    public List<Map<String, Object>> mine() {
        return support.mine(CurrentUser.id());
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        return support.mineDetail(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/messages")
    public Map<String, Object> reply(@PathVariable UUID id, @RequestBody ReplyRequest request) {
        support.userReply(CurrentUser.id(), id, request.message());
        return support.mineDetail(CurrentUser.id(), id);
    }
}

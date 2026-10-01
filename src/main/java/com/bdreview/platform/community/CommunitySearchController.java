package com.bdreview.platform.community;

import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.PageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Community search (V60) — public like the rest of the community GET surface. */
@RestController
@RequestMapping("/api/v1/community/search")
public class CommunitySearchController {

    private final CommunitySearchService searchService;

    public CommunitySearchController(CommunitySearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/posts")
    public ResponseEntity<PageResponse<CommunityPostResponse>> posts(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(searchService.searchPosts(q, page, size, CurrentUser.idOrNull()));
    }

    @GetMapping("/people")
    public ResponseEntity<PageResponse<CommunityFollowListItem>> people(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(searchService.searchPeople(q, page, size, CurrentUser.idOrNull()));
    }
}

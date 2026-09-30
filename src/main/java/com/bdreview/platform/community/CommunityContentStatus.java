package com.bdreview.platform.community;

/**
 * Moderation state of a community post/comment (V56). Orthogonal to {@code deletedAt}, which is
 * only ever the author's own delete.
 * <ul>
 *   <li>ACTIVE — visible to everyone.</li>
 *   <li>PENDING — held for review (new-user rule, banned word in FLAG mode, link from a new
 *       account); only the author sees it, labelled "Waiting for review".</li>
 *   <li>HIDDEN — taken out of circulation pending review (moderator "Hide", or auto-hidden after
 *       N reports); same visibility as PENDING.</li>
 *   <li>REMOVED — soft-removed by a moderator; the thread stays with a "[Removed by moderators]"
 *       placeholder and the author sees the reason. Restorable.</li>
 *   <li>DRAFT — (V58) an unpublished business post; only its author sees it and it is never
 *       listed in a moderation queue.</li>
 * </ul>
 */
public enum CommunityContentStatus {
    ACTIVE, PENDING, HIDDEN, REMOVED, DRAFT
}

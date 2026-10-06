package com.bdreview.platform.photomod;

public enum PhotoStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /** Removed by an admin from the business page. */
    DELETED,
    /** The uploader replaced or removed the photo before it was reviewed. */
    WITHDRAWN
}

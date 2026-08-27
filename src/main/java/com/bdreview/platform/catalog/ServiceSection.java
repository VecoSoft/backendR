package com.bdreview.platform.catalog;

/**
 * Which list a {@link ServiceOffering} row belongs to. Non-GYM kinds only ever
 * use {@link #OFFERING} (the "Services" list). GYM splits its list in two:
 * {@code OFFERING} = membership plans, {@code FACILITY} = amenity names.
 */
public enum ServiceSection {
    OFFERING,
    FACILITY
}

package com.bdreview.platform.commerce;

/** Which catalog table an order line came from. Phase A only uses MENU_ITEM; PRODUCT is Phase B. */
public enum OrderItemSource {
    MENU_ITEM,
    PRODUCT
}

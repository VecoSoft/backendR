package com.bdreview.platform.admin.form;

import com.bdreview.platform.business.CategoryKind;
import lombok.Data;

import java.util.UUID;

/**
 * Shared form-backing bean for the small reference-data lookup tables
 * (Category, City, BusinessAttribute all just need a name; Area additionally
 * needs its parent City; Category additionally carries its {@link CategoryKind}).
 */
@Data
public class ReferenceItemForm {
    private String name;
    private UUID cityId; // only used when adding an Area
    private CategoryKind kind = CategoryKind.GENERAL; // only used when adding/editing a Category
}

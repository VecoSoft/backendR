package com.bdreview.platform.admin.form;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.PriceTier;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Thymeleaf form-backing bean for creating/editing a {@link Business} from the admin panel. */
@Data
public class BusinessForm {

    private UUID id;
    private String name;
    private UUID categoryId;
    private UUID cityId;
    private UUID areaId;
    private String contactNumber;
    private String operatingHours;
    private String description;
    private String coverPhotoUrl;
    private Double latitude;
    private Double longitude;
    private PriceTier priceTier;
    private boolean verified;
    private List<UUID> attributeIds = new ArrayList<>();
    /** Brand → Branches — the admin panel is the only place a listing can be linked to a brand
     *  owned by a *different* account (self-service linking lives on BusinessService#create). */
    private UUID brandId;

    public static BusinessForm from(Business business) {
        BusinessForm form = new BusinessForm();
        form.setId(business.getId());
        form.setName(business.getName());
        form.setCategoryId(business.getCategory().getId());
        form.setCityId(business.getCity().getId());
        form.setAreaId(business.getArea().getId());
        form.setContactNumber(business.getContactNumber());
        form.setOperatingHours(business.getOperatingHours());
        form.setDescription(business.getDescription());
        form.setCoverPhotoUrl(business.getCoverPhotoUrl());
        form.setLatitude(business.getLocation().getY());
        form.setLongitude(business.getLocation().getX());
        form.setPriceTier(business.getPriceTier());
        form.setVerified(business.isVerified());
        form.setAttributeIds(business.getAttributes().stream().map(a -> a.getId()).toList());
        form.setBrandId(business.getBrandId());
        return form;
    }
}

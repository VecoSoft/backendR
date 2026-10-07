package com.bdreview.platform.admin.form;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRole;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.UUID;

/** Thymeleaf form-backing bean for admin user create/edit/reset-password pages. */
@Data
public class UserForm {

    private UUID id;

    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    @NotBlank(message = "Name is required")
    private String name;

    private String preferredLanguage = "en";
    private UserRole role = UserRole.ADMIN;
    private String password;
    /** V67 permission role for a new admin account — least privilege by default. */
    private String adminRole = "SUPPORT";

    public static UserForm from(User user) {
        UserForm form = new UserForm();
        form.setId(user.getId());
        form.setPhoneNumber(user.getPhoneNumber());
        form.setName(user.getName());
        form.setPreferredLanguage(user.getPreferredLanguage());
        form.setRole(user.getRole());
        return form;
    }
}

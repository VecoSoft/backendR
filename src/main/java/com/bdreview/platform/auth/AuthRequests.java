package com.bdreview.platform.auth;

import jakarta.validation.constraints.Size;

/** V70 request bodies for Google sign-in, the code screens and adding an e-mail to a signed-in account. */
public final class AuthRequests {

    private AuthRequests() {
    }

    /** The Google Identity Services credential (an ID token). */
    public record GoogleLoginRequest(@Size(max = 4096) String idToken, UserRole context) {
    }

    public record EmailCodeRequest(@Size(max = 320) String email, @Size(max = 20) String code) {
    }

    public record EmailOnlyRequest(@Size(max = 320) String email) {
    }

    public record AddEmailRequest(@Size(max = 320) String email, @Size(max = 200) String password,
                                  @Size(max = 200) String confirmPassword) {
    }

    public record LinkGoogleRequest(@Size(max = 4096) String idToken) {
    }
}

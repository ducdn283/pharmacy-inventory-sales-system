package com.example.project.context;

import com.example.project.constant.RoleConstants;
import com.example.project.security.AccountPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Single, isolated point that reads "who is the current user" from Spring Security.
 *
 * <p>The source of truth is the {@link AccountPrincipal} placed in the
 * {@link SecurityContextHolder} at login by {@code CustomAccountDetailsService}. There is no
 * session-based or default-role fallback: if there is no authenticated {@link AccountPrincipal}
 * every accessor returns {@code null}, and callers must treat that as "not signed in" rather
 * than assuming any role.</p>
 */
@Component
public class CurrentUserContext {

    /** The authenticated principal, or {@code null} for anonymous/unauthenticated requests. */
    public AccountPrincipal getPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof AccountPrincipal accountPrincipal ? accountPrincipal : null;
    }

    public boolean isAuthenticated() {
        return getPrincipal() != null;
    }

    /** Bare primary role (e.g. {@code "OWNER"}) of the signed-in user, or {@code null}. */
    public String getCurrentRole() {
        AccountPrincipal principal = getPrincipal();
        return principal == null ? null : principal.getPrimaryRole();
    }

    public String getCurrentAccountName() {
        AccountPrincipal principal = getPrincipal();
        return principal == null ? null : principal.getDisplayName();
    }

    public Integer getCurrentAccountId() {
        AccountPrincipal principal = getPrincipal();
        return principal == null ? null : principal.getAccountId();
    }

    /** Whether the signed-in user is the Owner. */
    public boolean isOwner() {
        return RoleConstants.OWNER.equals(getCurrentRole());
    }

    /** Whether the signed-in user is a Pharmacist. */
    public boolean isPharmacist() {
        return RoleConstants.PHARMACIST.equals(getCurrentRole());
    }
}

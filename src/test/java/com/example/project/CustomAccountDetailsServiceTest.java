package com.example.project;

import com.example.project.entity.Account;
import com.example.project.entity.Accountpermission;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.AccountpermissionRepository;
import com.example.project.security.AccountPrincipal;
import com.example.project.service.CustomAccountDetailsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Unit test (no Spring, no DB) for how {@link CustomAccountDetailsService} turns AccountPermission
 * rows into the authenticated principal, single-store: no branch selection any more — the account
 * signs in with {@code loadUserByLoginId(loginId)} and gets its one system-wide role. Only
 * {@code OWNER}, {@code PHARMACIST} and {@code ACCOUNTANT} may sign in; the legacy
 * {@code CHIEF_PHARMACIST} role alone is rejected (merged into {@code OWNER}), and {@code OWNER}
 * wins when an account somehow holds more than one role row.
 */
@ExtendWith(MockitoExtension.class)
class CustomAccountDetailsServiceTest {

    @Mock
    AccountRepository accountRepository;
    @Mock
    AccountpermissionRepository accountpermissionRepository;
    @InjectMocks
    CustomAccountDetailsService service;

    @Test
    void signInReturnsPrincipalWithItsSingleRole() {
        when(accountRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("u", "u"))
                .thenReturn(Optional.of(activeAccount()));
        when(accountpermissionRepository.findByAccountId(1))
                .thenReturn(List.of(perm(5, "PHARMACIST")));

        AccountPrincipal principal = service.loadUserByLoginId("u");

        assertEquals("PHARMACIST", principal.getPrimaryRole());
        Set<String> authorities = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        assertEquals(Set.of("ROLE_PHARMACIST"), authorities);
    }

    @Test
    void signInWithEmailInsteadOfUsernameReturnsPrincipal() {
        // findByUsernameIgnoreCaseOrEmailIgnoreCase checks the SAME loginId against both columns —
        // logging in with an email takes the identical code path as a username, just matching the
        // other column, so there is no separate branch to exercise beyond proving it also resolves.
        when(accountRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("u@example.com", "u@example.com"))
                .thenReturn(Optional.of(activeAccount()));
        when(accountpermissionRepository.findByAccountId(1))
                .thenReturn(List.of(perm(5, "PHARMACIST")));

        AccountPrincipal principal = service.loadUserByLoginId("u@example.com");

        assertEquals("PHARMACIST", principal.getPrimaryRole());
    }

    @Test
    void ownerWinsWhenAccountHoldsMultipleRoleRows() {
        when(accountRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("u", "u"))
                .thenReturn(Optional.of(activeAccount()));
        when(accountpermissionRepository.findByAccountId(1))
                .thenReturn(List.of(perm(5, "PHARMACIST"), perm(6, "OWNER")));

        AccountPrincipal principal = service.loadUserByLoginId("u");

        assertEquals("OWNER", principal.getPrimaryRole());
    }

    @Test
    void accountWithNoValidRoleCannotSignIn() {
        when(accountRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("u", "u"))
                .thenReturn(Optional.of(activeAccount()));
        when(accountpermissionRepository.findByAccountId(1))
                .thenReturn(List.of(perm(1, "SOMETHING_UNKNOWN")));

        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByLoginId("u"));
    }

    @Test
    void legacyChiefPharmacistAloneCannotSignIn() {
        // CHIEF_PHARMACIST was merged into OWNER — a leftover row with only this role must not
        // authenticate on its own.
        when(accountRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("u", "u"))
                .thenReturn(Optional.of(activeAccount()));
        when(accountpermissionRepository.findByAccountId(1))
                .thenReturn(List.of(perm(1, "CHIEF_PHARMACIST")));

        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByLoginId("u"));
    }

    @Test
    void inactiveAccountIsRejected() {
        Account inactive = activeAccount();
        inactive.setStatus(false);
        when(accountRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("u", "u"))
                .thenReturn(Optional.of(inactive));

        assertThrows(DisabledException.class, () -> service.loadUserByLoginId("u"));
    }

    @Test
    void accountWithBlankPasswordCannotSignIn() {
        Account noPassword = activeAccount();
        noPassword.setPassword("  ");
        when(accountRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("u", "u"))
                .thenReturn(Optional.of(noPassword));

        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByLoginId("u"));
    }

    @Test
    void blankLoginIdIsRejected() {
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByLoginId("   "));
    }

    @Test
    void unknownLoginIdIsRejected() {
        when(accountRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("nobody", "nobody"))
                .thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByLoginId("nobody"));
    }

    private Account activeAccount() {
        Account account = new Account();
        account.setId(1);
        account.setName("Test User");
        account.setUsername("u");
        account.setEmail("u@example.com");
        account.setPassword("$2a$10$examplehashexamplehashexampleha");
        account.setStatus(true);
        return account;
    }

    private Accountpermission perm(int id, String role) {
        Accountpermission permission = new Accountpermission();
        permission.setId(id);
        permission.setRole(role);
        return permission;
    }
}

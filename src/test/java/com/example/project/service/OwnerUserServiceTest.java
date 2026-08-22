package com.example.project.service;

import com.example.project.constant.RoleConstants;
import com.example.project.dto.request.OwnerUserCreateRequest;
import com.example.project.entity.Account;
import com.example.project.entity.Accountpermission;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.AccountpermissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests (no Spring, no DB) for {@link OwnerUserService}.
 *
 * <p>Scope: {@code createUser()} (the "CreateUser" function) plus {@code deactivateUser()}/
 * {@code activateUser()} — the only two methods that mutate an already-existing account, i.e. the
 * closest real analogue to an "UpdateUser" function; {@code OwnerUserService} has no method literally
 * named {@code updateUser}/{@code editUser} and {@link com.example.project.controller.OwnerUserController}
 * exposes no edit route, only create/deactivate/activate.</p>
 */
@ExtendWith(MockitoExtension.class)
class OwnerUserServiceTest {

    @Mock
    AccountRepository accountRepository;
    @Mock
    AccountpermissionRepository accountpermissionRepository;
    @Mock
    PasswordEncoder passwordEncoder;
    @InjectMocks
    OwnerUserService service;

    private OwnerUserCreateRequest validRequest() {
        OwnerUserCreateRequest request = new OwnerUserCreateRequest();
        request.setName("Nguyen Van A");
        request.setUsername("nguyenvana");
        request.setEmail("a@example.com");
        request.setPhoneNumber("0912345678");
        request.setPassword("secret123");
        request.setRole(RoleConstants.PHARMACIST);
        request.setStatus(true);
        return request;
    }

    // ---- createUser ----

    @Test
    void createUser_savesAccountAndPermission_whenRequestIsValid() {
        OwnerUserCreateRequest request = validRequest();
        when(accountRepository.existsByUsernameIgnoreCase("nguyenvana")).thenReturn(false);
        when(accountRepository.existsByEmailIgnoreCase("a@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("ENC(secret123)");
        Account saved = new Account();
        saved.setId(42);
        when(accountRepository.save(any(Account.class))).thenReturn(saved);
        when(accountpermissionRepository.findMaxId()).thenReturn(7);

        service.createUser(request);

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        Account persisted = accountCaptor.getValue();
        assertThat(persisted.getName()).isEqualTo("Nguyen Van A");
        assertThat(persisted.getUsername()).isEqualTo("nguyenvana");
        assertThat(persisted.getEmail()).isEqualTo("a@example.com");
        assertThat(persisted.getPhoneNumber()).isEqualTo("0912345678");
        assertThat(persisted.getPassword()).isEqualTo("ENC(secret123)");
        assertThat(persisted.getStatus()).isTrue();

        ArgumentCaptor<Accountpermission> permissionCaptor = ArgumentCaptor.forClass(Accountpermission.class);
        verify(accountpermissionRepository).save(permissionCaptor.capture());
        Accountpermission persistedPermission = permissionCaptor.getValue();
        assertThat(persistedPermission.getId()).isEqualTo(8);
        assertThat(persistedPermission.getAccountID()).isSameAs(saved);
        assertThat(persistedPermission.getRole()).isEqualTo(RoleConstants.PHARMACIST);
    }

    @Test
    void createUser_defaultsToInactive_whenStatusIsNull() {
        OwnerUserCreateRequest request = validRequest();
        request.setStatus(null);
        when(accountRepository.existsByUsernameIgnoreCase("nguyenvana")).thenReturn(false);
        when(accountRepository.existsByEmailIgnoreCase("a@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("ENC(secret123)");
        when(accountRepository.save(any(Account.class))).thenReturn(new Account());
        when(accountpermissionRepository.findMaxId()).thenReturn(0);

        service.createUser(request);

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getStatus()).isFalse();
    }

    @Test
    void createUser_savesAccountAndPermission_whenRoleIsAccountant() {
        OwnerUserCreateRequest request = validRequest();
        request.setRole(RoleConstants.ACCOUNTANT);
        when(accountRepository.existsByUsernameIgnoreCase("nguyenvana")).thenReturn(false);
        when(accountRepository.existsByEmailIgnoreCase("a@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("ENC(secret123)");
        when(accountRepository.save(any(Account.class))).thenReturn(new Account());
        when(accountpermissionRepository.findMaxId()).thenReturn(0);

        service.createUser(request);

        ArgumentCaptor<Accountpermission> permissionCaptor = ArgumentCaptor.forClass(Accountpermission.class);
        verify(accountpermissionRepository).save(permissionCaptor.capture());
        assertThat(permissionCaptor.getValue().getRole()).isEqualTo(RoleConstants.ACCOUNTANT);
    }

    @Test
    void createUser_throws_whenRoleIsInvalid() {
        OwnerUserCreateRequest request = validRequest();
        request.setRole("SOMETHING_UNKNOWN");

        assertThatThrownBy(() -> service.createUser(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vai trò không hợp lệ");

        verify(accountRepository, never()).save(any());
    }

    @Test
    void createUser_throws_whenRoleIsOwner() {
        OwnerUserCreateRequest request = validRequest();
        request.setRole(RoleConstants.OWNER);

        assertThatThrownBy(() -> service.createUser(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không được tạo thêm");

        verify(accountRepository, never()).save(any());
    }

    @Test
    void createUser_throws_whenUsernameAlreadyExists() {
        OwnerUserCreateRequest request = validRequest();
        when(accountRepository.existsByUsernameIgnoreCase("nguyenvana")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tên đăng nhập đã tồn tại");

        verify(accountRepository, never()).save(any());
    }

    @Test
    void createUser_throws_whenEmailAlreadyExists() {
        OwnerUserCreateRequest request = validRequest();
        when(accountRepository.existsByUsernameIgnoreCase("nguyenvana")).thenReturn(false);
        when(accountRepository.existsByEmailIgnoreCase("a@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email đã tồn tại");

        verify(accountRepository, never()).save(any());
    }

    // ---- deactivateUser / activateUser (the closest thing to "update user") ----

    @Test
    void deactivateUser_disablesAccount_whenActiveAndNotOwner() {
        Account account = new Account();
        account.setId(1);
        account.setStatus(true);
        when(accountRepository.findById(1)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findByAccountId(1))
                .thenReturn(List.of(perm(RoleConstants.PHARMACIST)));

        service.deactivateUser(1);

        assertThat(account.getStatus()).isFalse();
        verify(accountRepository).save(account);
    }

    @Test
    void deactivateUser_throws_whenAccountNotFound() {
        when(accountRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivateUser(99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không tìm thấy tài khoản");
    }

    @Test
    void deactivateUser_throws_whenAccountIsOwner() {
        Account account = new Account();
        account.setId(1);
        account.setStatus(true);
        when(accountRepository.findById(1)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findByAccountId(1))
                .thenReturn(List.of(perm(RoleConstants.OWNER)));

        assertThatThrownBy(() -> service.deactivateUser(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không thể vô hiệu hóa tài khoản Chủ sở hữu");

        verify(accountRepository, never()).save(any());
    }

    @Test
    void deactivateUser_throws_whenAlreadyInactive() {
        Account account = new Account();
        account.setId(1);
        account.setStatus(false);
        when(accountRepository.findById(1)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findByAccountId(1))
                .thenReturn(List.of(perm(RoleConstants.PHARMACIST)));

        assertThatThrownBy(() -> service.deactivateUser(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("đã bị vô hiệu hóa");
    }

    @Test
    void activateUser_enablesAccount_whenInactiveAndNotOwner() {
        Account account = new Account();
        account.setId(1);
        account.setStatus(false);
        when(accountRepository.findById(1)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findByAccountId(1))
                .thenReturn(List.of(perm(RoleConstants.ACCOUNTANT)));

        service.activateUser(1);

        assertThat(account.getStatus()).isTrue();
        verify(accountRepository).save(account);
    }

    @Test
    void activateUser_throws_whenAccountNotFound() {
        when(accountRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activateUser(99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không tìm thấy tài khoản");
    }

    @Test
    void activateUser_throws_whenAccountIsOwner() {
        Account account = new Account();
        account.setId(1);
        account.setStatus(false);
        when(accountRepository.findById(1)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findByAccountId(1))
                .thenReturn(List.of(perm(RoleConstants.OWNER)));

        assertThatThrownBy(() -> service.activateUser(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không thể thao tác trạng thái tài khoản Chủ sở hữu");

        verify(accountRepository, never()).save(any());
    }

    @Test
    void activateUser_throws_whenAlreadyActive() {
        Account account = new Account();
        account.setId(1);
        account.setStatus(true);
        when(accountRepository.findById(1)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findByAccountId(1))
                .thenReturn(List.of(perm(RoleConstants.PHARMACIST)));

        assertThatThrownBy(() -> service.activateUser(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("đang hoạt động");
    }

    private Accountpermission perm(String role) {
        Accountpermission permission = new Accountpermission();
        permission.setRole(role);
        return permission;
    }
}

package com.example.project.service;

import com.example.project.dto.request.ProfileUpdateRequest;
import com.example.project.dto.response.ProfileViewResponse;
import com.example.project.entity.Account;
import com.example.project.entity.Accountpermission;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.AccountpermissionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountpermissionRepository accountpermissionRepository;

    @InjectMocks
    private ProfileService profileService;

    @Test
    void getProfile_accountExistsWithAccountantRole_shouldReturnProfileSuccessfully() {
        Integer accountId = 1;

        Account account = createAccount(
                accountId,
                "Nguyễn Thị Lan",
                "lannguyen",
                "lan.nguyen@pharmamanager.vn",
                "0987654321",
                true
        );

        Accountpermission  permission = createPermission("accountant");

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findProfilePermissionsByAccountId(accountId))
                .thenReturn(List.of(permission));

        ProfileViewResponse result = profileService.getProfile(accountId);

        assertNotNull(result);
        assertEquals(accountId, result.getAccountId());
        assertEquals("Nguyễn Thị Lan", result.getName());
        assertEquals("lannguyen", result.getUsername());
        assertEquals("lan.nguyen@pharmamanager.vn", result.getEmail());
        assertEquals("0987654321", result.getPhoneNumber());
        assertEquals("Kế toán", result.getRole());
        assertTrue(result.getStatus());

        verify(accountRepository).findById(accountId);
        verify(accountpermissionRepository).findProfilePermissionsByAccountId(accountId);
    }

    @Test
    void getProfile_accountNotFound_shouldThrowRuntimeException() {
        Integer accountId = 999;

        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> profileService.getProfile(accountId)
        );

        assertEquals("Không tìm thấy tài khoản", exception.getMessage());

        verify(accountRepository).findById(accountId);
        verify(accountpermissionRepository, never()).findProfilePermissionsByAccountId(any());
    }

    @Test
    void getProfile_noPermissions_shouldReturnDefaultRoleAndDefaultBranch() {
        Integer accountId = 2;

        Account account = createAccount(
                accountId,
                "User No Permission",
                "nopermission",
                "nopermission@gmail.com",
                "0900000001",
                true
        );

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findProfilePermissionsByAccountId(accountId))
                .thenReturn(List.of());

        ProfileViewResponse result = profileService.getProfile(accountId);

        assertEquals("Chưa phân quyền", result.getRole());
    }

    @Test
    void getProfile_multiplePermissions_shouldJoinDistinctRoles() {
        Integer accountId = 3;

        Account account = createAccount(
                accountId,
                "Trần Anh Vũ",
                "vuta03",
                "vuta03@gmail.com",
                "0329377669",
                true
        );

        List<Accountpermission> permissions = List.of(
                createPermission("pharmacist"),
                createPermission("chief_pharmacist")
        );

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findProfilePermissionsByAccountId(accountId))
                .thenReturn(permissions);

        ProfileViewResponse result = profileService.getProfile(accountId);

        // CHIEF_PHARMACIST đã bị gộp vào OWNER, nên formatRole() ánh xạ "chief_pharmacist" thành
        // "Chủ nhà thuốc" chứ không còn "Dược sĩ trưởng" — hai quyền vẫn ra hai nhãn khác nhau nên
        // phép nối distinct vẫn được kiểm đúng như ý định ban đầu của test.
        assertEquals("Dược sĩ, Chủ nhà thuốc", result.getRole());
    }

    @Test
    void getProfile_duplicateRoles_shouldRemoveDuplicates() {
        Integer accountId = 4;

        Account account = createAccount(
                accountId,
                "Vũ Thị Hằng",
                "hangvt02",
                "vuthihang01@gmail.com",
                "0983276660",
                true
        );

        List<Accountpermission> permissions = List.of(
                createPermission("pharmacist"),
                createPermission("pharmacist"),
                createPermission("pharmacist")
        );

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findProfilePermissionsByAccountId(accountId))
                .thenReturn(permissions);

        ProfileViewResponse result = profileService.getProfile(accountId);

        assertEquals("Dược sĩ", result.getRole());
    }

    @Test
    void getProfile_blankRole_shouldReturnDefaultRole() {
        Integer accountId = 5;

        Account account = createAccount(
                accountId,
                "Blank User",
                "blankuser",
                "blank@gmail.com",
                "0866767746",
                true
        );

        List<Accountpermission> permissions = List.of(
                createPermission(null),
                createPermission(""),
                createPermission("   ")
        );

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findProfilePermissionsByAccountId(accountId))
                .thenReturn(permissions);

        ProfileViewResponse result = profileService.getProfile(accountId);

        assertEquals("Chưa phân quyền", result.getRole());
    }

    @Test
    void getProfile_roleIsCashier_shouldDisplayVietnameseRoleName() {
        // KNOWN STALE (see CLAUDE.md): CASHIER was removed project-wide, so formatRole() no longer
        // maps it to "Thu ngân" and this assertion fails. Left as-is intentionally — not fixed here.
        Integer accountId = 6;

        Account account = createAccount(
                accountId,
                "Nguyễn Đăng Trường",
                "truongnd06",
                "truongnd06@gmail.com",
                "0349618003",
                true
        );

        Accountpermission permission = createPermission("cashier");

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findProfilePermissionsByAccountId(accountId))
                .thenReturn(List.of(permission));

        ProfileViewResponse result = profileService.getProfile(accountId);

        assertEquals("Thu ngân", result.getRole());
    }

    @Test
    void getProfile_roleIsOwner_shouldDisplayOwnerVietnameseRoleName() {
        Integer accountId = 7;

        Account account = createAccount(
                accountId,
                "Trần Nguyễn Ngọc",
                "ngoctn01",
                "hangngocub@gmail.com",
                "0900000007",
                true
        );

        Accountpermission permission = createPermission("owner");

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findProfilePermissionsByAccountId(accountId))
                .thenReturn(List.of(permission));

        ProfileViewResponse result = profileService.getProfile(accountId);

        assertEquals("Chủ nhà thuốc", result.getRole());
    }

    @Test
    void getProfile_roleIsNumberThree_shouldDisplayAccountantRoleName() {
        Integer accountId = 8;

        Account account = createAccount(
                accountId,
                "Cường Thịnh",
                "thinhvc04",
                "cuongthinhvu12@gmail.com",
                "0922553838",
                true
        );

        Accountpermission permission = createPermission("3");

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findProfilePermissionsByAccountId(accountId))
                .thenReturn(List.of(permission));

        ProfileViewResponse result = profileService.getProfile(accountId);

        assertEquals("Kế toán", result.getRole());
    }

    @Test
    void getProfile_unknownRole_shouldKeepOriginalRoleValue() {
        Integer accountId = 9;

        Account account = createAccount(
                accountId,
                "Unknown Role User",
                "unknownrole",
                "unknown@gmail.com",
                "0900000009",
                true
        );

        Accountpermission permission = createPermission("inventory_staff");

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountpermissionRepository.findProfilePermissionsByAccountId(accountId))
                .thenReturn(List.of(permission));

        ProfileViewResponse result = profileService.getProfile(accountId);

        assertEquals("inventory_staff", result.getRole());
    }

    @Test
    void updateProfile_accountExists_shouldUpdateNameEmailAndTrimPhoneNumber() {
        Integer accountId = 10;

        Account account = createAccount(
                accountId,
                "Old Name",
                "oldusername",
                "old@gmail.com",
                "0900000010",
                true
        );

        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setName("New Name");
        request.setEmail("new@gmail.com");
        request.setPhoneNumber(" 0987654321 ");

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        profileService.updateProfile(accountId, request);

        assertEquals("New Name", account.getName());
        assertEquals("new@gmail.com", account.getEmail());
        assertEquals("0987654321", account.getPhoneNumber());

        verify(accountRepository).findById(accountId);
        verify(accountRepository).save(account);
    }

    @Test
    void updateProfile_accountNotFound_shouldThrowRuntimeExceptionAndNotSave() {
        Integer accountId = 999;

        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setName("New Name");
        request.setEmail("new@gmail.com");
        request.setPhoneNumber("0987654321");

        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> profileService.updateProfile(accountId, request)
        );

        assertEquals("Không tìm thấy tài khoản", exception.getMessage());

        verify(accountRepository).findById(accountId);
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void updateProfile_phoneNumberIsNull_shouldThrowNullPointerExceptionAndNotSave() {
        Integer accountId = 11;

        Account account = createAccount(
                accountId,
                "Null Phone User",
                "nullphone",
                "nullphone@gmail.com",
                "0900000011",
                true
        );

        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setName("Null Phone User Updated");
        request.setEmail("updated@gmail.com");
        request.setPhoneNumber(null);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertThrows(
                NullPointerException.class,
                () -> profileService.updateProfile(accountId, request)
        );

        verify(accountRepository).findById(accountId);
        verify(accountRepository, never()).save(any(Account.class));
    }

    private Account createAccount(Integer id,
                                  String name,
                                  String username,
                                  String email,
                                  String phoneNumber,
                                  Boolean status) {
        Account account = new Account();
        account.setId(id);
        account.setName(name);
        account.setUsername(username);
        account.setEmail(email);
        account.setPhoneNumber(phoneNumber);
        account.setStatus(status);
        return account;
    }

    private Accountpermission createPermission(String role) {
        Accountpermission permission = new Accountpermission();
        permission.setRole(role);
        return permission;
    }
}
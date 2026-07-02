package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UnitSupervisorRepository unitSupervisorRepository;
    private final UnitOperatorRepository unitOperatorRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleService roleService;
    private final PasswordEncoder passwordEncoder;

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    @Transactional
    public User create(String username, String fullName, String password, boolean active, List<Long> roleIds) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("نام کاربری «" + username + "» قبلاً ثبت شده است.");
        }
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(username.trim());
        user.setFullName(fullName != null ? fullName.trim() : null);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setActive(active);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);
        roleService.assignRolesToUser(user.getId(), roleIds);
        return user;
    }

    @Transactional
    public void update(Long id, String username, String fullName, boolean active, List<Long> roleIds) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("کاربر یافت نشد."));
        if (!user.getUsername().equals(username.trim()) && userRepository.existsByUsername(username.trim())) {
            throw new IllegalArgumentException("نام کاربری «" + username + "» قبلاً ثبت شده است.");
        }
        user.setUsername(username.trim());
        user.setFullName(fullName != null ? fullName.trim() : null);
        user.setActive(active);
        user.setUpdatedAt(System.currentTimeMillis());
        userRepository.save(user);
        roleService.assignRolesToUser(id, roleIds);
    }

    @Transactional
    public void changePassword(Long id, String newPassword) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("کاربر یافت نشد."));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(System.currentTimeMillis());
        userRepository.save(user);
    }

    @Transactional
    public void delete(Long id) {
        if (unitSupervisorRepository.existsByUserId(id) || unitOperatorRepository.existsByUserId(id)) {
            throw new IllegalStateException("این کاربر به واحد عملیاتی اختصاص داده شده و قابل حذف نیست.");
        }
        userRoleRepository.deleteByUserId(id);
        userRepository.deleteById(id);
    }
}

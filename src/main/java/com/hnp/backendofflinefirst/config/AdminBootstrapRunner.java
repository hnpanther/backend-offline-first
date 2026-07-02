package com.hnp.backendofflinefirst.config;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrapRunner implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        roleRepository.findByCode("ADMIN").ifPresent(adminRole -> {
            boolean hasAdmin = userRoleRepository.findAll().stream()
                    .anyMatch(ur -> adminRole.getId().equals(ur.getRoleId()));
            if (hasAdmin) return;

            long now = System.currentTimeMillis();
            User admin = new User();
            admin.setUsername("admin");
            admin.setFullName("مدیر سیستم");
            admin.setPasswordHash(passwordEncoder.encode("admin123"));
            admin.setActive(true);
            admin.setCreatedAt(now);
            admin.setUpdatedAt(now);
            userRepository.save(admin);

            UserRole ur = new UserRole();
            ur.setUserId(admin.getId());
            ur.setRoleId(adminRole.getId());
            userRoleRepository.save(ur);

            log.warn("Default admin user created: username=admin password=admin123 — change immediately.");
        });
    }
}

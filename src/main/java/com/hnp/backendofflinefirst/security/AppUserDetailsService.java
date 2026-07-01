package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.PermissionRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PermissionRepository permissionRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("کاربر یافت نشد: " + username));

        Set<String> roleCodes = new HashSet<>(userRoleRepository.findRoleCodesByUserId(user.getId()));
        Set<String> permissionCodes = new HashSet<>(permissionRepository.findPermissionCodesByUserId(user.getId()));

        return new AppUserDetails(user, roleCodes, permissionCodes);
    }
}

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
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a user, with their current roles and permissions, from the database.
 *
 * <p>Two entry points, for the two ways a request arrives. Form login looks a user up by the name
 * they typed; the API looks one up by the {@code uid} in a bearer token. Both build the same
 * principal from the same live rows, which is the point — see {@link ApiTokenAuthenticator} for
 * why the API path stopped trusting the token's own claims about what its holder may do.
 */
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
        return withAuthorities(user);
    }

    /**
     * The same principal, found by id — what an API request resolves its bearer token to.
     *
     * <p>By id rather than by the token's {@code sub}: a username changes and an id does not, so
     * a token issued before a rename still resolves to the same person instead of failing to
     * resolve at all. Same reasoning as {@code AppUserDetails.identityKey}, and the same bug
     * class as gotcha #82.
     *
     * <p>Returns empty rather than throwing when the user is gone. A deleted account is an
     * ordinary outcome on this path — the token outlives the row — and it is the caller's job to
     * turn that into a 401, not an exception's.
     */
    @Transactional(readOnly = true)
    public Optional<AppUserDetails> loadById(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return userRepository.findById(userId).map(this::withAuthorities);
    }

    private AppUserDetails withAuthorities(User user) {
        Set<String> roleCodes = new HashSet<>(userRoleRepository.findRoleCodesByUserId(user.getId()));
        Set<String> permissionCodes = new HashSet<>(permissionRepository.findPermissionCodesByUserId(user.getId()));
        return new AppUserDetails(user, roleCodes, permissionCodes);
    }
}

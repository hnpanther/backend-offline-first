package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.entity.User;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What makes two principals the same person, for Spring Security's concurrent-session control.
 *
 * <p>This used to be the username, and that made a session's identity change when the account
 * was renamed. Three things followed, each demonstrated below: the one-session-per-user limit
 * became bypassable, an administrator could no longer find a renamed user's session to close it,
 * and reusing a freed username conflated two different people.
 */
class AppUserDetailsIdentityTest {

    private static AppUserDetails principal(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setActive(true);
        return new AppUserDetails(user, Set.of(), Set.of());
    }

    @Test
    void twoLoginsOfTheSameUserAreTheSamePrincipal() {
        // The property the one-session-per-user limit is built on. If this ever fails,
        // maximumSessions(1) silently stops working.
        assertThat(principal(5L, "ali")).isEqualTo(principal(5L, "ali"));
        assertThat(principal(5L, "ali")).hasSameHashCodeAs(principal(5L, "ali"));
    }

    @Test
    void arenamedUserIsStillTheSamePrincipal() {
        AppUserDetails before = principal(5L, "old-name");
        AppUserDetails after = principal(5L, "new-name");

        assertThat(before).isEqualTo(after);
        assertThat(before).hasSameHashCodeAs(after);
    }

    @Test
    void reusingAFreedUsernameDoesNotConflateTwoPeople() {
        // User 5 was renamed away from "shared"; user 9 was then given it. They are not the
        // same session holder, and the registry must not treat them as one.
        assertThat(principal(5L, "shared")).isNotEqualTo(principal(9L, "shared"));
    }

    @Test
    void differentUsersAreDifferentPrincipals() {
        assertThat(principal(5L, "ali")).isNotEqualTo(principal(6L, "reza"));
    }

    @Test
    void anIdLessPrincipalStillComparesByUsername() {
        // Test fixtures and any unsaved user have no id. Nothing should regress for them.
        assertThat(principal(null, "ali")).isEqualTo(principal(null, "ali"));
        assertThat(principal(null, "ali")).hasSameHashCodeAs(principal(null, "ali"));
        assertThat(principal(null, "ali")).isNotEqualTo(principal(null, "reza"));
    }

    @Test
    void anIdNamespaceCannotCollideWithAUsername() {
        // A user whose id is 5 must not equal one whose username happens to be "5".
        assertThat(principal(5L, "ali")).isNotEqualTo(principal(null, "5"));
    }

    @Test
    void equalsAndHashCodeAgreeOnEveryPairTheyCallEqual() {
        // The contract, checked directly: a branching equals with a single-field hashCode is
        // exactly the bug this class is shaped to avoid.
        AppUserDetails[] all = {
                principal(5L, "a"), principal(5L, "b"), principal(6L, "a"),
                principal(null, "a"), principal(null, "b")
        };
        for (AppUserDetails left : all) {
            for (AppUserDetails right : all) {
                if (left.equals(right)) {
                    assertThat(left.hashCode())
                            .as("%s vs %s are equal and must hash the same", left.getUsername(), right.getUsername())
                            .isEqualTo(right.hashCode());
                }
            }
        }
    }

    @Test
    void isReflexiveSymmetricAndNullSafe() {
        AppUserDetails p = principal(5L, "ali");
        assertThat(p).isEqualTo(p);
        assertThat(p.equals(null)).isFalse();
        assertThat(p.equals("not a principal")).isFalse();
        assertThat(p.equals(principal(5L, "ali")) && principal(5L, "ali").equals(p)).isTrue();
    }
}

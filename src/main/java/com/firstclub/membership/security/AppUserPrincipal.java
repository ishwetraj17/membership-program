package com.firstclub.membership.security;

import com.firstclub.membership.entity.AppAccount;
import lombok.Getter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.List;

/**
 * Authenticated principal that also carries the linked membership user id, so controllers can
 * enforce "self or admin" ownership without an extra DB lookup.
 */
@Getter
public class AppUserPrincipal extends User {

    private final Long membershipUserId;
    private final String role;

    public AppUserPrincipal(AppAccount account) {
        super(account.getUsername(), account.getPasswordHash(), account.isEnabled(),
                true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name())));
        this.membershipUserId = account.getMembershipUserId();
        this.role = account.getRole().name();
    }
}

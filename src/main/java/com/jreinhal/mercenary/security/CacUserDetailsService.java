package com.jreinhal.mercenary.security;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

/**
 * UserDetailsService implementation for CAC/PIV X.509 certificate authentication.
 * This service is called by Spring Security's x509() filter when a client certificate
 * is presented. The username parameter contains the CN extracted from the certificate.
 */
@Service
public class CacUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(CacUserDetailsService.class);

    private final UserRepository userRepository;

    public CacUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
        log.info(">>> CacUserDetailsService initialized for X.509 certificate authentication <<<");
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.debug("Loading user by certificate CN: {}", username);

        // First try to find by username (CN from certificate)
        User user = userRepository.findByUsername(username).orElse(null);

        // If not found, try by externalId (full DN might be stored there)
        if (user == null) {
            user = userRepository.findByExternalId(username).orElse(null);
        }

        // Auto-provision new CAC users with minimal permissions
        if (user == null) {
            log.info("Auto-provisioning new CAC user: {} (requires admin approval for elevated access)", username);
            user = new User();
            user.setUsername(username);
            user.setDisplayName(username);
            user.setExternalId(username);
            user.setAuthProvider(User.AuthProvider.CAC);
            user.setRoles(Set.of(UserRole.VIEWER));
            user.setClearance(ClearanceLevel.UNCLASSIFIED);
            user.setAllowedSectors(Set.of(Department.GOVERNMENT));
            user.setCreatedAt(Instant.now());
            user.setActive(true);
            user.setPendingApproval(true); // Mark for admin review
            user = userRepository.save(user);
        }

        if (!user.isActive()) {
            log.warn("CAC user {} is deactivated", username);
            throw new UsernameNotFoundException("User account is deactivated: " + username);
        }

        // Update last login
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        return new CacUserDetails(user);
    }

    /**
     * UserDetails implementation that wraps our User model.
     */
    public static class CacUserDetails implements UserDetails {
        private final User user;

        public CacUserDetails(User user) {
            this.user = user;
        }

        public User getUser() {
            return user;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            for (UserRole role : user.getRoles()) {
                authorities.add(new SimpleGrantedAuthority(role.name()));
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
                for (UserRole.Permission permission : role.getPermissions()) {
                    authorities.add(new SimpleGrantedAuthority("PERM_" + permission.name()));
                }
            }
            return authorities;
        }

        @Override
        public String getPassword() {
            // CAC auth doesn't use passwords
            return "";
        }

        @Override
        public String getUsername() {
            return user.getUsername();
        }

        @Override
        public boolean isAccountNonExpired() {
            return true;
        }

        @Override
        public boolean isAccountNonLocked() {
            return user.isActive();
        }

        @Override
        public boolean isCredentialsNonExpired() {
            return true;
        }

        @Override
        public boolean isEnabled() {
            return user.isActive();
        }
    }
}

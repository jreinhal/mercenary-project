package com.jreinhal.mercenary.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Stub implementation for CAC/PIV UserDetailsService.
 * In a real implementation, this would look up users from an LDAP or database
 * based on the certificate Subject DN.
 */
@Service
public class CacUserDetailsService implements UserDetailsService {

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        throw new UsernameNotFoundException("CAC Authentication not yet fully implemented: " + username);
    }
}

package com.jreinhal.mercenary;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.service.CacAuthenticationService;
import com.jreinhal.mercenary.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AuthTest {

    @Mock
    private UserRepository userRepository;

    @Test
    public void testCacAuthentication_ValidHeader() {
        // Setup
        CacAuthenticationService authService = new CacAuthenticationService(userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest();

        // Simulate Nginx/Envoy forwarding the extracted DN
        String dn = "CN=John Wick,OU=Operations,O=Continental";
        request.addHeader("X-Client-Cert", dn);

        // Mock Repo behavior (User exists)
        User existingUser = new User();
        existingUser.setUsername("John Wick");
        existingUser.setActive(true);
        when(userRepository.findByExternalId(dn)).thenReturn(Optional.of(existingUser));

        // Execute
        User result = authService.authenticate(request);

        // Verify
        assertNotNull(result);
        assertEquals("John Wick", result.getUsername());
        verify(userRepository, times(1)).save(any(User.class)); // Verify last login updated
    }

    @Test
    public void testCacAuthentication_AutoProvision() {
        // Setup
        CacAuthenticationService authService = new CacAuthenticationService(userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest();

        String dn = "CN=New Recruit,OU=Training,O=Unknown";
        request.addHeader("X-Client-Cert", dn);

        // Mock Repo behavior (User does NOT exist)
        when(userRepository.findByExternalId(dn)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArguments()[0]); // Return what was saved

        // Execute
        User result = authService.authenticate(request);

        // Verify
        assertNotNull(result);
        assertEquals("New Recruit", result.getUsername());
        assertEquals("UNCLASSIFIED", result.getClearance().name());
        verify(userRepository, times(2)).save(any(User.class)); // 1 for create, 1 for login update
    }

    @Test
    public void testCacAuthentication_MissingHeader() {
        // Setup
        CacAuthenticationService authService = new CacAuthenticationService(userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest();
        // No headers

        // Execute
        User result = authService.authenticate(request);

        // Verify
        assertNull(result);
    }
}

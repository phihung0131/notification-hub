package org.example.tenantservice.config.security;

import lombok.RequiredArgsConstructor;
import org.example.tenantservice.model.Tenant;
import org.example.tenantservice.repository.TenantRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {
    private final TenantRepository tenantRepository;

    /**
     * Load user details by email (username)
     * @param email the email of the user
     * @return UserDetails object containing user information
     * @throws UsernameNotFoundException if user is not found
     */
    @Override
    @Cacheable(value = "userDetailsByEmail", key = "#email")
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Tenant tenant = tenantRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User Not Found with email: " + email));

        return new CustomUserDetails(tenant.getId(), tenant.getEmail(), tenant.getPassword(),
                tenant.getPermissions().stream()
                        .map(p -> new SimpleGrantedAuthority(p.getName()))
                        .collect(Collectors.toList()));
    }
}
package org.example.tenantservice.config.security;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Custom implementation of UserDetails to represent authenticated user information */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomUserDetails implements UserDetails {

    private String id;

    private String email;

    private String password;

    private Collection<? extends GrantedAuthority> authorities;

    /** Returns the authorities granted to the user */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return this.authorities;
    }

    /** Returns the password used to authenticate the user */
    @Override
    public String getPassword() {
        return this.password;
    }

    /** Returns the username used to authenticate the user (in this case, the email) */
    @Override
    @JsonIgnore
    public String getUsername() {
        return this.email;
    }

    /**
     * Indicates whether the user's account has expired. Always returns true in this implementation.
     */
    @Override
    @JsonIgnore
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * Indicates whether the user is locked or unlocked. Always returns true in this implementation.
     */
    @Override
    @JsonIgnore
    public boolean isAccountNonLocked() {
        return true;
    }

    /**
     * Indicates whether the user's credentials (password) has expired. Always returns true in this
     * implementation.
     */
    @Override
    @JsonIgnore
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * Indicates whether the user is enabled or disabled. Always returns true in this
     * implementation.
     */
    @Override
    @JsonIgnore
    public boolean isEnabled() {
        return true;
    }
}

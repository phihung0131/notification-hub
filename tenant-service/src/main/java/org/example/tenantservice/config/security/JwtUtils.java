package org.example.tenantservice.config.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for generating and validating JWT tokens
 */
@Slf4j
@Component
public class JwtUtils {
    @Value("${app.jwtSecret}")
    private String jwtSecret;

    @Value("${app.jwtExpirationMs}")
    private int jwtExpirationMs;

    private Key key;

    @PostConstruct
    public void init() {
        // Decode the Base64 secret to get the byte array
        byte[] keyBytes = Decoders.BASE64.decode(this.jwtSecret);
        // Create a secure Key object from the byte array
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generates a JWT token from an Authentication object.
     * @param authentication the authentication object containing user details
     * @return the generated JWT token as a String
     */
    public String generateJwtToken(Authentication authentication) {
        String username = authentication.getName();
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        return Jwts.builder()
                .subject(username)
                .claim("authorities", authorities)
                .issuedAt(new Date())
                .expiration(new Date((new Date()).getTime() + this.jwtExpirationMs))
                .signWith(this.key)
                .compact();
    }

    /**
     * Parses the JWT token to get all claims.
     * @param token the JWT token as a String
     * @return the Claims object containing all claims from the token
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith((javax.crypto.SecretKey) this.key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Extracts the username from the JWT token.
     * @param token the JWT token as a String
     * @return the username (subject) extracted from the token
     */
    public String getUserNameFromJwtToken(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Validates a JWT token.
     * @param authToken the JWT token as a String
     * @return true if the token is valid, false otherwise
     */
    public boolean validateJwtToken(String authToken) {
        try {
            // Only parsing the claims to check validity
            parseClaims(authToken);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.error("Invalid JWT token: {}", e.getMessage());
        }
        return false;
    }
}
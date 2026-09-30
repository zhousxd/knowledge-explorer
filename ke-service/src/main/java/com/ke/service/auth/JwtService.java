package com.ke.service.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(@Value("${ke.jwt.secret}") String secret,
                      @Value("${ke.jwt.access-ttl}") Duration accessTtl,
                      @Value("${ke.jwt.refresh-ttl}") Duration refreshTtl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public String issueAccess(Long userId, String role) { return issue(userId, role, "access", accessTtl); }
    public String issueRefresh(Long userId) { return issue(userId, null, "refresh", refreshTtl); }

    private String issue(Long userId, String role, String typ, Duration ttl) {
        var builder = Jwts.builder()
            .subject(String.valueOf(userId)).claim("typ", typ)
            .issuedAt(new Date()).expiration(Date.from(Instant.now().plus(ttl)))
            .signWith(key);
        if (role != null) builder.claim("role", role);
        return builder.compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}

package com.example.booking.security;

import com.example.booking.entity.Role;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/** Maps a verified JWT to an authentication whose principal is an {@link AuthenticatedUser}. */
@Component
public class JwtPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AuthenticatedUser principal = toPrincipal(jwt);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, jwt, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
    }

    private AuthenticatedUser toPrincipal(Jwt jwt) {
        try {
            return new AuthenticatedUser(
                    Long.valueOf(jwt.getSubject()),
                    Role.valueOf(jwt.getClaimAsString(JwtTokenService.ROLE_CLAIM)));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidBearerTokenException("Token carries invalid identity claims");
        }
    }
}

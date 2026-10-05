package com.example.booking.ratelimit;

import com.example.booking.config.RateLimitProperties;
import com.example.booking.exception.ErrorCode;
import com.example.booking.security.AuthenticatedUser;
import com.example.booking.security.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits login and registration per client IP, and booking and hold creation per authenticated user.
 * It runs after bearer-token authentication so per-user limits can use the verified identity, and before
 * authorization so rejected requests never reach the controllers.
 *
 * <p>The client address is the TCP peer. Behind a reverse proxy it is only the real client if forwarded headers
 * are enabled and the proxy is trusted ({@code FORWARD_HEADERS_STRATEGY=native} with {@code TRUSTED_PROXIES});
 * Tomcat then rewrites the remote address, and this filter never reads {@code X-Forwarded-For} itself, so a
 * client cannot choose its own rate-limit bucket. Whatever address results is validated as an IP literal
 * before it is used in a Redis key.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final String UNKNOWN_CLIENT = "unknown";
    /** IPv4 dotted quad, or IPv6 in hex groups optionally ending in an embedded IPv4 address. */
    private static final Pattern IP_LITERAL = Pattern.compile(
            "^(?:\\d{1,3}(?:\\.\\d{1,3}){3}"
                    + "|(?:[0-9a-fA-F]{0,4}:){2,7}(?:[0-9a-fA-F]{0,4}|\\d{1,3}(?:\\.\\d{1,3}){3}))$");

    private record Rule(String bucket, int limit, boolean perUser) {
    }

    private final RateLimiter limiter;
    private final RateLimitProperties properties;
    private final ErrorResponseWriter errorWriter;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties, ErrorResponseWriter errorWriter) {
        this.limiter = limiter;
        this.properties = properties;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = "POST".equals(request.getMethod()) ? ruleFor(request.getRequestURI()) : null;
        if (rule != null) {
            Optional<String> identity = rule.perUser() ? authenticatedUserId() : Optional.of(clientAddress(request.getRemoteAddr()));
            if (identity.isPresent()) {
                RateLimiter.Decision decision = limiter.check(rule.bucket(), identity.get(), rule.limit());
                if (!decision.allowed()) {
                    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
                    errorWriter.write(request, response, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED,
                            "Too many requests. Please retry later.");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    /** Returns the address if it is a plain IPv4 or IPv6 literal, otherwise a shared "unknown" bucket. */
    static String clientAddress(String remoteAddress) {
        return remoteAddress != null && IP_LITERAL.matcher(remoteAddress).matches() ? remoteAddress : UNKNOWN_CLIENT;
    }

    private Rule ruleFor(String path) {
        return switch (path) {
            case "/api/v1/auth/login" -> new Rule("login", properties.loginPerMinute(), false);
            case "/api/v1/auth/register" -> new Rule("register", properties.registerPerMinute(), false);
            case "/api/v1/bookings", "/api/v1/bookings/holds" ->
                    new Rule("booking", properties.bookingPerMinute(), true);
            default -> null;
        };
    }

    private Optional<String> authenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return Optional.of(String.valueOf(user.id()));
        }
        return Optional.empty();
    }
}

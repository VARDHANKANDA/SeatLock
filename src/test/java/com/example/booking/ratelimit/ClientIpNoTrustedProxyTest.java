package com.example.booking.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Forwarded headers are switched on but no proxy is trusted (the default {@code TRUSTED_PROXIES} is empty), so
 * a client that is not a trusted proxy still cannot choose its identity.
 */
@TestPropertySource(properties = {
        "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.internal-proxies="
})
class ClientIpNoTrustedProxyTest extends ClientIpTestSupport {

    @Test
    void forwardedHeadersFromAnUntrustedPeerAreIgnored() throws Exception {
        assertThat(loginAttempt("203.0.113.77")).isEqualTo(401);

        assertThat(loginIdentities()).singleElement().asString().matches(LOOPBACK_PEER);
    }
}

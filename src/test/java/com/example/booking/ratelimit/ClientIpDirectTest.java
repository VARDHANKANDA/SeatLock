package com.example.booking.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Default configuration: no proxy is trusted, so X-Forwarded-For can never choose the rate-limit identity. */
class ClientIpDirectTest extends ClientIpTestSupport {

    @Test
    void aDirectClientIsIdentifiedByItsTcpPeer() throws Exception {
        assertThat(loginAttempt(null)).isEqualTo(401);

        assertThat(loginIdentities()).singleElement().asString().matches(LOOPBACK_PEER);
    }

    @Test
    void aSpoofedForwardedHeaderIsIgnored() throws Exception {
        assertThat(loginAttempt("203.0.113.77")).isEqualTo(401);
        assertThat(loginAttempt("198.51.100.1, 203.0.113.78")).isEqualTo(401);

        assertThat(loginIdentities()).singleElement().asString().matches(LOOPBACK_PEER);
    }

    @Test
    void aMalformedForwardedHeaderIsIgnoredAndHarmless() throws Exception {
        assertThat(loginAttempt("not-an-ip")).isEqualTo(401);
        assertThat(loginAttempt("<script>, rl:login:*")).isEqualTo(401);

        assertThat(loginIdentities()).singleElement().asString().matches(LOOPBACK_PEER);
    }
}

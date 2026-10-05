package com.example.booking.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The test client plays the part of a trusted reverse proxy (its loopback address is the trusted peer). */
@TestPropertySource(properties = {
        "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1"
})
class ClientIpTrustedProxyTest extends ClientIpTestSupport {

    @Test
    void theForwardedClientAddressIsUsedWhenTheProxyIsTrusted() throws Exception {
        assertThat(loginAttempt("203.0.113.5")).isEqualTo(401);

        assertThat(loginIdentities()).containsExactly("203.0.113.5");
    }

    @Test
    void aClientSuppliedPrefixInTheChainCannotChooseTheIdentity() throws Exception {
        // A client sent "198.51.100.9"; the trusted proxy appended the address it actually saw.
        assertThat(loginAttempt("198.51.100.9, 203.0.113.5")).isEqualTo(401);

        assertThat(loginIdentities()).containsExactly("203.0.113.5");
    }

    @Test
    void countersAreSeparatePerForwardedClient() throws Exception {
        loginAttempt("203.0.113.5");
        loginAttempt("203.0.113.6");
        loginAttempt("203.0.113.6");

        assertThat(loginIdentities()).containsExactlyInAnyOrder("203.0.113.5", "203.0.113.6");
        assertThat(redis.opsForValue().get("rl:login:203.0.113.6")).isEqualTo("2");
    }

    @Test
    void aMalformedForwardedAddressFallsIntoOneSharedUnknownBucket() throws Exception {
        assertThat(loginAttempt("not-an-ip")).isEqualTo(401);
        assertThat(loginAttempt("rl:login:*")).isEqualTo(401);
        assertThat(loginAttempt("<script>")).isEqualTo(401);

        assertThat(loginIdentities()).containsExactly("unknown");
    }
}

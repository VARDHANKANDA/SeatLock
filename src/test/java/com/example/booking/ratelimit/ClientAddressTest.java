package com.example.booking.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ClientAddressTest {

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "203.0.113.5", "10.0.0.255", "0:0:0:0:0:0:0:1", "::1", "2001:db8::1",
            "fe80::1ff:fe23:4567:890a", "::ffff:192.0.2.1"})
    void ipLiteralsAreUsedAsTheRateLimitIdentity(String address) {
        assertThat(RateLimitFilter.clientAddress(address)).isEqualTo(address);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-an-ip", "1.2.3", "1.2.3.4, 5.6.7.8", "1.2.3.4:8080", "<script>alert(1)</script>",
            "rl:login:*", "a b", "evil\r\nheader", "localhost", "1234:5678:9abc:def0:1234:5678:9abc:def0:1234:5678:9abc",
            "gggg::1", "999999999999999999999"})
    void anythingElseSharesOneUnknownBucketSoItCannotCreateArbitraryKeys(String address) {
        assertThat(RateLimitFilter.clientAddress(address)).isEqualTo(RateLimitFilter.UNKNOWN_CLIENT);
    }
}

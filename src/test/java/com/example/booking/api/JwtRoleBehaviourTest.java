package com.example.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.entity.Role;
import com.example.booking.entity.User;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Access tokens are stateless: the role is read from the token, which is signed. A role change in the database
 * therefore applies to tokens issued afterwards, and old tokens keep their role until they expire. Tokens cannot
 * be edited to claim a different role.
 */
class JwtRoleBehaviourTest extends IntegrationTestSupport {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    @Autowired
    private JwtEncoder jwtEncoder;

    @Test
    void aNewTokenCarriesTheCurrentRoleButAnExistingTokenKeepsItsOldRoleUntilExpiry() throws Exception {
        saveUser("user@example.com", Role.USER);
        String oldToken = login("user@example.com");
        createEvent(oldToken).andExpect(status().isForbidden());

        jdbc.update("update users set role = 'ADMIN' where email = 'user@example.com'");

        createEvent(oldToken).andExpect(status().isForbidden());
        String newToken = login("user@example.com");
        assertThat(roleClaim(newToken)).isEqualTo("ADMIN");
        createEvent(newToken).andExpect(status().isCreated());
    }

    @Test
    void aDemotedAdminKeepsAdminRightsOnlyUntilTheirTokenExpires() throws Exception {
        saveUser("admin@example.com", Role.ADMIN);
        String oldToken = login("admin@example.com");
        assertThat(roleClaim(oldToken)).isEqualTo("ADMIN");

        jdbc.update("update users set role = 'USER' where email = 'admin@example.com'");

        createEvent(oldToken).andExpect(status().isCreated());
        String newToken = login("admin@example.com");
        assertThat(roleClaim(newToken)).isEqualTo("USER");
        createEvent(newToken).andExpect(status().isForbidden());
    }

    @Test
    void aTokenWhosePayloadWasEditedToClaimAdminIsRejected() throws Exception {
        saveUser("user@example.com", Role.USER);
        String token = login("user@example.com");
        String[] parts = token.split("\\.");
        ObjectNode payload = (ObjectNode) objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        payload.put("role", "ADMIN");
        String forged = parts[0] + "." + URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload)) + "." + parts[2];

        createEvent(forged).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnsignedTokenWithAlgorithmNoneIsRejected() throws Exception {
        long expiry = Instant.now().plusSeconds(600).getEpochSecond();
        String header = URL_ENCODER.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = URL_ENCODER.encodeToString(("{\"iss\":\"booking-api\",\"sub\":\"1\",\"role\":\"ADMIN\",\"exp\":"
                + expiry + "}").getBytes(StandardCharsets.UTF_8));

        createEvent(header + "." + payload + ".").andExpect(status().isUnauthorized());
        createEvent(header + "." + payload).andExpect(status().isUnauthorized());
    }

    @Test
    void aCorrectlySignedTokenWithAnUnknownRoleIsRejected() throws Exception {
        User user = saveUser("user@example.com", Role.USER);

        createEvent(signed(user.getId(), "SUPERUSER")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCorrectlySignedTokenWithANonNumericSubjectIsRejected() throws Exception {
        createEvent(signed("not-a-number", "ADMIN")).andExpect(status().isUnauthorized());
    }

    private String signed(Object subject, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("booking-api").subject(String.valueOf(subject))
                .issuedAt(now).expiresAt(now.plusSeconds(600)).claim("role", role).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", TEST_PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private String roleClaim(String token) throws Exception {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        return objectMapper.readTree(payload).get("role").asText();
    }

    private ResultActions createEvent(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/events").header(AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Gala", "venue", "Hall",
                        "eventDateTime", Instant.now().plusSeconds(86_400).toString()))));
    }
}

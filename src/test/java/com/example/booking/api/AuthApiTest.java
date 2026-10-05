package com.example.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.entity.Role;
import com.example.booking.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class AuthApiTest extends IntegrationTestSupport {

    @Autowired
    private JwtEncoder jwtEncoder;

    private static final Map<String, String> REGISTRATION =
            Map.of("name", "Ada Lovelace", "email", "Ada@Example.com", "password", "Secret123");

    @Test
    void registersUserWithoutExposingPasswordAndStoresBcryptHash() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json(REGISTRATION)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String stored = jdbc.queryForObject("select password_hash from users where email = 'ada@example.com'", String.class);
        assertThat(stored).startsWith("$2").doesNotContain("Secret123");
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        register();

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Other", "email", "ADA@example.com", "password", "Secret123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/register"));
    }

    @Test
    void invalidRegistrationReturnsFieldDetails() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "", "email", "not-an-email", "password", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'password')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'name')]").exists());
    }

    @Test
    void passwordLongerThanBcryptLimitInBytesIsRejectedAsInvalidInput() throws Exception {
        String multiByte = "Aa1" + "é".repeat(40);

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Ada", "email", "ada@example.com", "password", multiByte))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[?(@.field == 'password')]").exists());
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void loginReturnsTokenThatAuthenticatesProfileRequests() throws Exception {
        register();

        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "ada@example.com", "password", "Secret123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() throws Exception {
        register();

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "ada@example.com", "password", "Wrong-password1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void protectedEndpointRequiresToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() throws Exception {
        User user = saveUser("ada@example.com", Role.USER);
        Instant now = Instant.now();
        NimbusJwtEncoder forger = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec("a-different-secret-that-is-long-enough-123".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("booking-api").subject(String.valueOf(user.getId()))
                .issuedAt(now).expiresAt(now.plusSeconds(600)).claim("role", "ADMIN").build();
        String forged = forger.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        User user = saveUser("ada@example.com", Role.USER);
        Instant past = Instant.now().minusSeconds(3600);

        mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + signedToken(user, "booking-api", past)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        User user = saveUser("ada@example.com", Role.USER);

        mockMvc.perform(get("/api/v1/users/me")
                        .header(AUTHORIZATION, "Bearer " + signedToken(user, "someone-else", Instant.now())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void swaggerAndOpenApiDocumentAreServed() throws Exception {
        String api = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(api).get("paths");
        assertThat(paths.has("/api/v1/bookings")).isTrue();
        assertThat(paths.has("/api/v1/auth/login")).isTrue();
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void sensitiveActuatorEndpointsAreNotExposed() throws Exception {
        User admin = saveUser("admin@example.com", Role.ADMIN);
        mockMvc.perform(get("/actuator/env").header(AUTHORIZATION, bearerFor(admin)))
                .andExpect(status().isNotFound());
    }

    /** Signs a token with the application's real key; {@code issuedAt} + 10 minutes is the expiry. */
    private String signedToken(User user, String issuer, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(String.valueOf(user.getId()))
                .issuedAt(issuedAt).expiresAt(issuedAt.plusSeconds(600)).claim("role", user.getRole().name()).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private void register() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json(REGISTRATION)))
                .andExpect(status().isCreated());
    }
}

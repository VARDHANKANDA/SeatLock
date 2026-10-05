package com.example.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.entity.Role;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Malformed and hostile requests must produce a clean 4xx in the standard format, never a 500 or internals. */
class ApiRobustnessTest extends IntegrationTestSupport {

    private String user;

    @BeforeEach
    void setUp() {
        user = bearerFor(saveUser("user@example.com", Role.USER));
    }

    @Test
    void unknownPathIsNotFoundInTheStandardFormat() throws Exception {
        expectCleanError(mockMvc.perform(get("/api/v1/does-not-exist").header(AUTHORIZATION, user)), 404);
    }

    @Test
    void wrongHttpMethodIsMethodNotAllowed() throws Exception {
        expectCleanError(mockMvc.perform(delete("/api/v1/bookings/me").header(AUTHORIZATION, user)), 405);
        expectCleanError(mockMvc.perform(get("/api/v1/auth/login").header(AUTHORIZATION, user)), 405);
    }

    @Test
    void unsupportedMediaTypeIsRejected() throws Exception {
        expectCleanError(mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, user)
                .contentType(MediaType.TEXT_PLAIN).content("eventId=1")), 415);
    }

    @Test
    void missingOrUnreadableBodiesAreBadRequests() throws Exception {
        expectCleanError(mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, user)
                .contentType(MediaType.APPLICATION_JSON)), 400);
        expectCleanError(mockMvc.perform(post("/api/v1/bookings/holds").header(AUTHORIZATION, user)
                .contentType(MediaType.APPLICATION_JSON).content("[1,2,3]")), 400);
        expectCleanError(mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":{\"nested\":true},\"seatId\":1}")), 400);
    }

    @Test
    void nonNumericAndOverflowingIdentifiersAreBadRequests() throws Exception {
        expectCleanError(mockMvc.perform(get("/api/v1/bookings/abc").header(AUTHORIZATION, user)), 400);
        expectCleanError(mockMvc.perform(get("/api/v1/events/99999999999999999999").header(AUTHORIZATION, user)), 400);
        expectCleanError(mockMvc.perform(post("/api/v1/bookings/holds/xyz/confirm").header(AUTHORIZATION, user)), 400);
        expectCleanError(mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":99999999999999999999999,\"seatId\":1}")), 400);
    }

    @Test
    void negativePagingParametersAreBadRequests() throws Exception {
        expectCleanError(mockMvc.perform(get("/api/v1/events?page=-1").header(AUTHORIZATION, user)), 400);
        expectCleanError(mockMvc.perform(get("/api/v1/events?size=0").header(AUTHORIZATION, user)), 400);
        expectCleanError(mockMvc.perform(get("/api/v1/bookings/me?size=abc").header(AUTHORIZATION, user)), 400);
    }

    @Test
    void injectionStyleInputIsTreatedAsPlainData() throws Exception {
        String payload = "' OR '1'='1'; DROP TABLE users; --";

        expectCleanError(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", payload, "password", payload)))), 401);
        expectCleanError(mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "x", "email", payload, "password", "Secret123")))), 400);
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
    }

    @Test
    void idempotencyKeyWithControlCharactersIsRejected() throws Exception {
        expectCleanError(mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, user)
                .header("Idempotency-Key", "key with spaces").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":1,\"seatId\":1}")), 400);
        expectCleanError(mockMvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, user)
                .header("Idempotency-Key", "k".repeat(256)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":1,\"seatId\":1}")), 400);
    }

    @Test
    void tokenFormatsThatAreNotBearerTokensAreUnauthorized() throws Exception {
        for (String header : new String[] {"Basic dXNlcjpwYXNz", "Bearer", "Bearer ", "bearer.token.value", "Token abc"}) {
            expectCleanError(mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, header)), 401);
        }
    }

    @Test
    void adminOnlyEndpointsRejectAnonymousAndRegularUsers() throws Exception {
        for (String path : new String[] {"/api/v1/events", "/api/v1/events/1/seats"}) {
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post(path).header(AUTHORIZATION, user).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(delete("/api/v1/events/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/events/1").header(AUTHORIZATION, user)).andExpect(status().isForbidden());
    }

    private void expectCleanError(ResultActions result, int expectedStatus) throws Exception {
        String body = result.andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.code").isString())
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception", "org.springframework", "com.example", "\tat ", "SQL");
    }
}

package com.example.booking.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.booking.IntegrationTestSupport;
import com.example.booking.entity.Event;
import com.example.booking.entity.Role;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The cache lifetime comes from configuration, and entries really do expire, bounding how stale a read can be. */
@TestPropertySource(properties = "app.cache.event-ttl-seconds=3")
class EventCacheTtlTest extends IntegrationTestSupport {

    @Test
    void entriesExpireAfterTheConfiguredTtlAndTheNextReadSeesTheDatabase() throws Exception {
        String user = bearerFor(saveUser("user@example.com", Role.USER));
        Event event = saveEvent();
        String path = "/api/v1/events/" + event.getId();
        mockMvc.perform(get(path).header(AUTHORIZATION, user)).andExpect(jsonPath("$.name").value("Concert"));
        assertThat(redis.getExpire("event:" + event.getId())).isBetween(1L, 3L);
        jdbc.update("update events set name = 'Changed behind the cache' where id = ?", event.getId());
        mockMvc.perform(get(path).header(AUTHORIZATION, user)).andExpect(jsonPath("$.name").value("Concert"));

        await().atMost(Duration.ofSeconds(15)).until(() -> !redis.hasKey("event:" + event.getId()));

        mockMvc.perform(get(path).header(AUTHORIZATION, user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Changed behind the cache"));
    }
}

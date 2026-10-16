package com.example.bloom;

import com.example.bloom.service.BloomMaintenanceService;
import com.example.bloom.service.EmailBloomFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "demo.seed.enabled=true",
        "demo.seed.rows=5000",
        "bloom.expected-insertions=50000",
        "bloom.warmup-async=false",
        "bloom.snapshot-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class UserApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired EmailBloomFilter bloom;
    @Autowired MeterRegistry registry;
    @Autowired BloomMaintenanceService maintenance;

    @BeforeEach
    void ensureBuilt() {
        // ApplicationReadyEvent already built it synchronously (warmup-async=false)
        assertThat(bloom.isReady()).isTrue();
    }

    private double counter(String result) {
        var c = registry.find("bloom.lookup").tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void existingSeededUserIsFound() throws Exception {
        mvc.perform(get("/api/users").param("email", "User42@Example.com")) // case-insensitive
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user42@example.com"));
    }

    @Test
    void unknownEmailIsAnsweredWithoutTouchingDb() throws Exception {
        double before = counter("definitely_absent");
        int notFound = 0;
        for (int i = 0; i < 200; i++) {
            var res = mvc.perform(get("/api/users").param("email", "ghost" + i + "@nowhere.org")).andReturn();
            if (res.getResponse().getStatus() == 404) notFound++;
        }
        assertThat(notFound).isEqualTo(200); // never a wrong answer
        // with a 1% filter, (almost) all of them are rejected by the filter alone
        assertThat(counter("definitely_absent") - before).isGreaterThanOrEqualTo(190);
    }

    @Test
    void newlyCreatedUserIsImmediatelyFindable() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"New.Person@Example.com\",\"name\":\"New Person\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/users").param("email", "new.person@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Person"));
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        String body = "{\"email\":\"dup@example.com\",\"name\":\"Dup\"}";
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void rebuildKeepsServingCorrectAnswers() throws Exception {
        assertThat(maintenance.rebuildAsync()).isTrue();
        for (int i = 0; i < 50; i++) {
            mvc.perform(get("/api/users").param("email", "user" + i + "@example.com")).andExpect(status().isOk());
        }
    }

    @Test
    void statsEndpointWorks() throws Exception {
        mvc.perform(get("/api/admin/bloom/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter.ready").value(true));
    }
}

package com.github.guoyyit.realtimeleaderboardjava.web;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 接口测试：验证从分数写入到名次读取的完整 HTTP 链路。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScoreControllerTest {

    static Path tempDir;

    static {
        try {
            tempDir = Files.createTempDirectory("leaderboard-rest-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void cleanup() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            }
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("leaderboard.data-dir", tempDir::toString);
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void submitAndQueryRank() throws Exception {
        mockMvc.perform(post("/api/leaderboards/rest-b1/scores")
                        .contentType("application/json")
                        .content("{\"userId\":\"u1\",\"score\":100,\"seq\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.score").value(100))
                .andExpect(jsonPath("$.rank").value(1));

        mockMvc.perform(get("/api/leaderboards/rest-b1/users/u1/rank"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u1"))
                .andExpect(jsonPath("$.score").value(100))
                .andExpect(jsonPath("$.rank").value(1));
    }

    @Test
    void duplicateSubmission_returnsOkAndIgnored() throws Exception {
        mockMvc.perform(post("/api/leaderboards/rest-b2/scores")
                        .contentType("application/json")
                        .content("{\"userId\":\"u1\",\"score\":100,\"seq\":1}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/leaderboards/rest-b2/scores")
                        .contentType("application/json")
                        .content("{\"userId\":\"u1\",\"score\":200,\"seq\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE_IGNORED"))
                .andExpect(jsonPath("$.score").value(100));
    }

    @Test
    void rangePagination() throws Exception {
        mockMvc.perform(get("/api/leaderboards/rest-b3/range?start=1&end=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.entries").isEmpty())
                .andExpect(jsonPath("$.totalUsers").value(0));

        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/api/leaderboards/rest-b3/scores")
                            .contentType("application/json")
                            .content("{\"userId\":\"u" + i + "\",\"score\":" + (i * 10) + ",\"seq\":1}"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/leaderboards/rest-b3/range?start=1&end=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].userId").value("u5"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].userId").value("u4"))
                .andExpect(jsonPath("$.entries[1].rank").value(2))
                .andExpect(jsonPath("$.totalUsers").value(5))
                .andExpect(jsonPath("$.totalRanks").value(5));

        mockMvc.perform(get("/api/leaderboards/rest-b3/range?start=-5&end=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startRank").value(1))
                .andExpect(jsonPath("$.endRank").value(1))
                .andExpect(jsonPath("$.entries.length()").value(1));

        mockMvc.perform(get("/api/leaderboards/rest-b3/range?start=4&end=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startRank").value(4))
                .andExpect(jsonPath("$.endRank").value(5))
                .andExpect(jsonPath("$.entries.length()").value(2));
    }

    @Test
    void neighbors() throws Exception {
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/api/leaderboards/rest-b4/scores")
                            .contentType("application/json")
                            .content("{\"userId\":\"u" + i + "\",\"score\":" + (i * 10) + ",\"seq\":1}"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/leaderboards/rest-b4/users/u3/neighbors?range=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].userId").value("u4"))
                .andExpect(jsonPath("$[1].userId").value("u3"))
                .andExpect(jsonPath("$[2].userId").value("u2"));
    }

    @Test
    void userNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/leaderboards/rest-b5/users/nonexistent/rank"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidInput_returns400() throws Exception {
        mockMvc.perform(post("/api/leaderboards/rest-b6/scores")
                        .contentType("application/json")
                        .content("{\"userId\":\"u1\",\"score\":-1,\"seq\":1}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/leaderboards/rest-b6/scores")
                        .contentType("application/json")
                        .content("{\"userId\":\"u1\",\"score\":100,\"seq\":0}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/leaderboards/rest-b6/scores")
                        .contentType("application/json")
                        .content("{\"userId\":\"u1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listBoards() throws Exception {
        mockMvc.perform(get("/api/leaderboards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}

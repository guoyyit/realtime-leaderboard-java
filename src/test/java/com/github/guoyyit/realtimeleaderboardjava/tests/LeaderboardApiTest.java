package com.github.guoyyit.realtimeleaderboardjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LeaderboardApiTest {

    @TempDir
    static Path walDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("leaderboard.wal-dir", walDir::toString);
        registry.add("leaderboard.rate-limit-per-second", () -> "100000");
        registry.add("leaderboard.rate-limit-burst", () -> "100000");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void submitThenQueryRankingsAndUserRank() throws Exception {
        mvc.perform(post("/api/leaderboards/daily/scores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\",\"score\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rank").value(1))
                .andExpect(jsonPath("$.score").value(100));
        mvc.perform(post("/api/leaderboards/daily/scores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"bob\",\"score\":200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rank").value(1));

        mvc.perform(get("/api/leaderboards/daily/rankings?fromRank=1&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].userId").value("bob"))
                .andExpect(jsonPath("$.entries[1].userId").value("alice"))
                .andExpect(jsonPath("$.total").value(2));

        mvc.perform(get("/api/leaderboards/daily/users/alice/rank?context=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.rank").value(2))
                .andExpect(jsonPath("$.neighbors.length()").value(2));

        // 用户不存在：确定性 found=false
        mvc.perform(get("/api/leaderboards/daily/users/nobody/rank"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false));
        // 榜单不存在：空分页
        mvc.perform(get("/api/leaderboards/ghost/rankings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void invalidInputReturns400() throws Exception {
        mvc.perform(post("/api/leaderboards/daily/scores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\",\"score\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ARGUMENT"));
        mvc.perform(post("/api/leaderboards/daily/scores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/leaderboards/daily/rankings?fromRank=0"))
                .andExpect(status().isBadRequest());
    }
}

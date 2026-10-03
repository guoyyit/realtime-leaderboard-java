package com.github.guoyyit.realtimeleaderboardjava.leaderboard.web;

import com.github.guoyyit.realtimeleaderboardjava.leaderboard.model.PageResult;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.model.ScoreEntry;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.model.SubmitResult;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.model.SubmitStatus;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.service.LeaderboardService;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.web.dto.ScoreSubmitRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 排行榜 REST 接口。
 */
@RestController
@RequestMapping("/api/leaderboards")
public class ScoreController {

    private final LeaderboardService service;

    public ScoreController(LeaderboardService service) {
        this.service = service;
    }

    /** 提交分数。重复提交或乱序到达返回对应状态；限流返回 429。 */
    @PostMapping("/{boardId}/scores")
    public ResponseEntity<SubmitResult> submit(@PathVariable String boardId,
                                                @RequestBody ScoreSubmitRequest request) {
        if (request.getUserId() == null || request.getScore() == null || request.getSeq() == null) {
            throw new IllegalArgumentException("userId, score and seq are required");
        }
        SubmitResult result = service.submit(boardId, request.getUserId(), request.getScore(), request.getSeq());
        if (result.status() == SubmitStatus.RATE_LIMITED) {
            return ResponseEntity.status(429).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /** 按稠密名次区间分页（名次 1 起，闭区间，越界自动钳制）。 */
    @GetMapping("/{boardId}/range")
    public PageResult range(@PathVariable String boardId,
                            @RequestParam(defaultValue = "1") long start,
                            @RequestParam(defaultValue = "10") long end) {
        return service.range(boardId, start, end);
    }

    /** 查询指定用户的成绩与名次；用户或榜单不存在时返回 404。 */
    @GetMapping("/{boardId}/users/{userId}/rank")
    public ResponseEntity<ScoreEntry> userRank(@PathVariable String boardId, @PathVariable String userId) {
        ScoreEntry entry = service.userRank(boardId, userId);
        return entry == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(entry);
    }

    /** 查询指定用户附近的排名（稠密名次在 [rank-range, rank+range] 内的所有用户）。 */
    @GetMapping("/{boardId}/users/{userId}/neighbors")
    public List<ScoreEntry> neighbors(@PathVariable String boardId,
                                      @PathVariable String userId,
                                      @RequestParam(defaultValue = "3") long range) {
        return service.neighbors(boardId, userId, range);
    }

    /** 列出所有榜单标识。 */
    @GetMapping
    public List<String> listBoards() {
        return service.listBoards();
    }
}

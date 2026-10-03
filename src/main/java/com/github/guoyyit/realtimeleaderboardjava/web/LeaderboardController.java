package com.github.guoyyit.realtimeleaderboardjava.web;

import com.github.guoyyit.realtimeleaderboardjava.core.Leaderboard.RankedEntry;
import com.github.guoyyit.realtimeleaderboardjava.service.LeaderboardService;
import com.github.guoyyit.realtimeleaderboardjava.web.dto.RankingPageView;
import com.github.guoyyit.realtimeleaderboardjava.web.dto.RankedEntryView;
import com.github.guoyyit.realtimeleaderboardjava.web.dto.SubmitScoreRequest;
import com.github.guoyyit.realtimeleaderboardjava.web.dto.UserRankView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/leaderboards")
public class LeaderboardController {

    private final LeaderboardService service;

    public LeaderboardController(LeaderboardService service) {
        this.service = service;
    }

    @PostMapping("/{boardId}/scores")
    public RankedEntryView submit(@PathVariable String boardId, @RequestBody SubmitScoreRequest request) {
        if (request == null || request.score() == null) {
            throw new IllegalArgumentException("score is required");
        }
        return toView(service.submit(boardId, request.userId(), request.score()));
    }

    @GetMapping("/{boardId}/rankings")
    public RankingPageView rankings(@PathVariable String boardId,
                                    @RequestParam(defaultValue = "1") int fromRank,
                                    @RequestParam(defaultValue = "20") int size) {
        List<RankedEntry> entries = service.rangeByRank(boardId, fromRank, size);
        return new RankingPageView(boardId, fromRank, size, entries.size(),
                entries.stream().map(LeaderboardController::toView).toList());
    }

    @GetMapping("/{boardId}/users/{userId}/rank")
    public UserRankView userRank(@PathVariable String boardId, @PathVariable String userId,
                                 @RequestParam(defaultValue = "2") int context) {
        return service.neighborhoodOf(boardId, userId, context)
                .map(neighbors -> {
                    List<RankedEntryView> views = neighbors.stream()
                            .map(LeaderboardController::toView)
                            .toList();
                    RankedEntryView self = views.stream()
                            .filter(view -> view.userId().equals(userId))
                            .findFirst()
                            .orElseThrow();
                    return new UserRankView(boardId, userId, true, self.rank(), self.score(), views);
                })
                .orElseGet(() -> new UserRankView(boardId, userId, false, null, null, List.of()));
    }

    private static RankedEntryView toView(RankedEntry entry) {
        return new RankedEntryView(entry.rank(), entry.record().userId(),
                entry.record().score(), entry.record().updatedAtMs());
    }
}

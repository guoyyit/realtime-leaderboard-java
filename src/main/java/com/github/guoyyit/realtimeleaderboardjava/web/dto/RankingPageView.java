package com.github.guoyyit.realtimeleaderboardjava.web.dto;

import java.util.List;

/**
 * 名次区间分页结果。
 */
public record RankingPageView(String boardId, int fromRank, int size, long total,
                              List<RankedEntryView> entries) {
}

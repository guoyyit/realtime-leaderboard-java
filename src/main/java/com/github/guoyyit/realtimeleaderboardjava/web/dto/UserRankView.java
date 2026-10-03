package com.github.guoyyit.realtimeleaderboardjava.web.dto;

import java.util.List;

/**
 * 指定用户的名次及邻居。
 */
public record UserRankView(String boardId, String userId, boolean found,
                           Integer rank, Long score, List<RankedEntryView> neighbors) {
}

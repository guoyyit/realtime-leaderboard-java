package com.github.guoyyit.realtimeleaderboardjava.web.dto;

/**
 * 带名次的榜单条目视图。
 */
public record RankedEntryView(int rank, String userId, long score, long updatedAtMs) {
}

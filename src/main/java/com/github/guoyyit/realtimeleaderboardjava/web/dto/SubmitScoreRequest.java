package com.github.guoyyit.realtimeleaderboardjava.web.dto;

/**
 * 提交分数请求体。
 */
public record SubmitScoreRequest(String userId, Long score) {
}

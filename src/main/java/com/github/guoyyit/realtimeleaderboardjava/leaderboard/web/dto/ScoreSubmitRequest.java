package com.github.guoyyit.realtimeleaderboardjava.leaderboard.web.dto;

/**
 * 分数提交请求体。
 */
public class ScoreSubmitRequest {

    private String userId;
    private Long score;
    private Long seq;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getScore() {
        return score;
    }

    public void setScore(Long score) {
        this.score = score;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }
}

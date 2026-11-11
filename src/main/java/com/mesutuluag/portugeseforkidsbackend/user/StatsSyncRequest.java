package com.mesutuluag.portugeseforkidsbackend.user;

import java.util.Map;

public class StatsSyncRequest {
    private int activeSecondsDelta;
    private Map<String, Integer> timeByModeDelta;
    private Map<String, Integer> gamesCompletedDelta;
    private Map<String, Integer> storiesByContextDelta;
    private int scoreDelta;
    private Map<String, Integer> scoreByModeDelta;
    private int speechListenDelta;
    private String clientDate;

    public StatsSyncRequest() {}

    public int getActiveSecondsDelta() {
        return activeSecondsDelta;
    }

    public void setActiveSecondsDelta(int activeSecondsDelta) {
        this.activeSecondsDelta = activeSecondsDelta;
    }

    public Map<String, Integer> getTimeByModeDelta() {
        return timeByModeDelta;
    }

    public void setTimeByModeDelta(Map<String, Integer> timeByModeDelta) {
        this.timeByModeDelta = timeByModeDelta;
    }

    public Map<String, Integer> getGamesCompletedDelta() {
        return gamesCompletedDelta;
    }

    public void setGamesCompletedDelta(Map<String, Integer> gamesCompletedDelta) {
        this.gamesCompletedDelta = gamesCompletedDelta;
    }

    public Map<String, Integer> getStoriesByContextDelta() {
        return storiesByContextDelta;
    }

    public void setStoriesByContextDelta(Map<String, Integer> storiesByContextDelta) {
        this.storiesByContextDelta = storiesByContextDelta;
    }

    public int getScoreDelta() {
        return scoreDelta;
    }

    public void setScoreDelta(int scoreDelta) {
        this.scoreDelta = scoreDelta;
    }

    public Map<String, Integer> getScoreByModeDelta() {
        return scoreByModeDelta;
    }

    public void setScoreByModeDelta(Map<String, Integer> scoreByModeDelta) {
        this.scoreByModeDelta = scoreByModeDelta;
    }

    public int getSpeechListenDelta() {
        return speechListenDelta;
    }

    public void setSpeechListenDelta(int speechListenDelta) {
        this.speechListenDelta = speechListenDelta;
    }

    public String getClientDate() {
        return clientDate;
    }

    public void setClientDate(String clientDate) {
        this.clientDate = clientDate;
    }
}

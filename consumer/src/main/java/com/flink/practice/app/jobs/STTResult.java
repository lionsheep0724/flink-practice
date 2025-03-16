package com.flink.practice.app.jobs;

import java.io.Serializable;

public class STTResult implements Serializable {
    private String sessionId;
    private String text;
    private double startTime;
    private double endTime;
    private boolean forced;
    
    public STTResult() {
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
    
    public String getText() {
        return text;
    }
    
    public void setText(String text) {
        this.text = text;
    }
    
    public double getStartTime() {
        return startTime;
    }
    
    public void setStartTime(double startTime) {
        this.startTime = startTime;
    }
    
    public double getEndTime() {
        return endTime;
    }
    
    public void setEndTime(double endTime) {
        this.endTime = endTime;
    }
    
    public boolean isForced() {
        return forced;
    }
    
    public void setForced(boolean forced) {
        this.forced = forced;
    }
    
    @Override
    public String toString() {
        return "STTResult{" +
                "sessionId='" + sessionId + '\'' +
                ", text='" + text + '\'' +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                ", forced=" + forced +
                '}';
    }
} 
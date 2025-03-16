package com.flink.practice.app.jobs;

import java.io.Serializable;

public class VadResult implements Serializable {
  private String sessionId;
  private String result;
  private byte[] audioData;
  private double startTime;
  private double endTime;

  // 기본 생성자 (필수)
  public VadResult() {}

  public VadResult(String sessionId, String result) {
    this.sessionId = sessionId;
    this.result = result;
  }

  // Getter & Setter
  public String getSessionId() {
    return sessionId;
  }
  
  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }
  
  public String getResult() {
    return result;
  }
  
  public void setResult(String result) {
    this.result = result;
  }
  
  public byte[] getAudioData() {
    return audioData;
  }
  
  public void setAudioData(byte[] audioData) {
    this.audioData = audioData;
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

  @Override
  public String toString() {
    return "VadResult{" +
        "sessionId='" + sessionId + '\'' +
        ", result='" + result + '\'' +
        ", audioDataLength=" + (audioData != null ? audioData.length : 0) +
        ", startTime=" + startTime +
        ", endTime=" + endTime +
        '}';
  }
}

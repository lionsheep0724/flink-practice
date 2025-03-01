package com.flink.practice.app.jobs;

import java.io.Serializable;

public class VadResult implements Serializable {
  private String sessionId;
  private String result;

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

  @Override
  public String toString() {
    return "VadResult{" +
        "sessionId='" + sessionId + '\'' +
        ", result='" + result + '\'' +
        '}';
  }
}

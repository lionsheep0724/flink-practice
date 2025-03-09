package com.flink.practice.app.jobs;

import java.io.Serializable;

public class AudioChunk implements Serializable {
  private String sessionId;
  private byte[] data;
  private boolean isFirstChunk;
  private boolean isLastChunk;

  // 기본 생성자
  public AudioChunk() { }

  // 모든 필드를 초기화하는 생성자
  public AudioChunk(String sessionId, byte[] data, boolean isFirstChunk, boolean isLastChunk) {
    this.sessionId = sessionId;
    this.data = data;
    this.isFirstChunk = isFirstChunk;
    this.isLastChunk = isLastChunk;
  }

  // Getter 및 Setter 메서드
  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public byte[] getData() {
    return data;
  }

  public void setData(byte[] data) {
    this.data = data;
  }

  public boolean isFirstChunk() {
    return isFirstChunk;
  }

  public boolean isLastChunk() {
    return isLastChunk;
  }

}

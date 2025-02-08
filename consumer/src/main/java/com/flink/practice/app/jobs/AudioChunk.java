package com.flink.practice.app.jobs;

import java.io.Serializable;

public class AudioChunk implements Serializable {
  private String sessionId;
  private byte[] data;

  // 기본 생성자 (필수)
  public AudioChunk() { }

  // 모든 필드를 초기화하는 생성자
  public AudioChunk(String sessionId, byte[] data) {
    this.sessionId = sessionId;
    this.data = data;
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
}

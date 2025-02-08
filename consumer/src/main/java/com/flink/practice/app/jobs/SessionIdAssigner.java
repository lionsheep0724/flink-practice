package com.flink.practice.app.jobs;

import org.apache.flink.api.common.functions.RichMapFunction;
import java.util.UUID;

public class SessionIdAssigner extends RichMapFunction<AudioChunk, AudioChunk> {
  @Override
  public AudioChunk map(AudioChunk value) throws Exception {
    if (value.getSessionId() == null || value.getSessionId().isEmpty()) {
      // 새 요청마다 고유한 sessionID 생성 (예: UUID)
      value.setSessionId(UUID.randomUUID().toString());
    }
    return value;
  }
}

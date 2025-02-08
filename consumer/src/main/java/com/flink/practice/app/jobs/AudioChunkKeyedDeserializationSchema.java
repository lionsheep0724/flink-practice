package com.flink.practice.app.jobs;

import org.apache.flink.streaming.util.serialization.KeyedDeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import java.io.IOException;


public class AudioChunkKeyedDeserializationSchema implements KeyedDeserializationSchema<AudioChunk> {

  @Override
  public AudioChunk deserialize(byte[] key, byte[] message, String topic, int partition, long offset) throws IOException {
    // Kafka 메시지의 key는 sessionId로 가정 (UTF-8 인코딩된 문자열)
    String sessionId = key != null ? new String(key, "UTF-8") : null;
    // 메시지(byte[])는 오디오 데이터라고 가정
    return new AudioChunk(sessionId, message);
  }

  @Override
  public boolean isEndOfStream(AudioChunk nextElement) {
    return false;
  }

  @Override
  public TypeInformation<AudioChunk> getProducedType() {
    return Types.POJO(AudioChunk.class);
  }
}

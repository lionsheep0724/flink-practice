package com.flink.practice.app.jobs;

import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import java.io.IOException;


public class AudioChunkKeyedDeserializationSchema implements DeserializationSchema<AudioChunk> {

  @Override
  public AudioChunk deserialize(byte[] message) throws IOException {
    // 메시지(byte[])는 오디오 데이터라고 가정
    // 세션 ID는 이제 다른 방식으로 전달되므로 null로 설정
    return new AudioChunk(null, message);
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

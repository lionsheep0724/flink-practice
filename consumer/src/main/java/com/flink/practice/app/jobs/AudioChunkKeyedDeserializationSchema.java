package com.flink.practice.app.jobs;

import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import java.io.IOException;


public class AudioChunkKeyedDeserializationSchema implements DeserializationSchema<AudioChunk> {

  @Override
  public AudioChunk deserialize(byte[] message) throws IOException {
    return new AudioChunk(null, message, false, false);
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

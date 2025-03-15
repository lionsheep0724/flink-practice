package com.flink.practice.app.jobs;
import org.apache.flink.streaming.connectors.kafka.KafkaDeserializationSchema;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.kafka.common.header.Headers;
public class AudioChunkKeyedDeserializationSchema implements KafkaDeserializationSchema<AudioChunk> {

  @Override
  public TypeInformation<AudioChunk> getProducedType() {
      // POJO Type
      return Types.POJO(AudioChunk.class);
  }

  @Override
  public boolean isEndOfStream(AudioChunk nextElement) {
      return false;
  }

  @Override
  public AudioChunk deserialize(ConsumerRecord<byte[], byte[]> record) throws IOException {
    String sessionId = (record.key() != null) ? new String(record.key(), StandardCharsets.UTF_8) : null;
    byte[] data = record.value();

    // header 파싱
    Headers headers = record.headers();
    boolean first = false;
    boolean last = false;

    if (headers.lastHeader("isFirst") != null) {
        first = true;
    }
    if (headers.lastHeader("isLast") != null) {
        last = true;
    }
    return new AudioChunk(sessionId, data, first, last);
}
}

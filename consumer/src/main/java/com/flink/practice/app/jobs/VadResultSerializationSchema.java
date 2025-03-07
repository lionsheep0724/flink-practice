package com.flink.practice.app.jobs;

import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.kafka.clients.producer.ProducerRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.Nullable;

public class VadResultSerializationSchema implements KafkaRecordSerializationSchema<VadResult> {

  private final String targetTopic;
  private transient ObjectMapper mapper;

  public VadResultSerializationSchema(String targetTopic) {
    this.targetTopic = targetTopic;
  }

  @Override
  public ProducerRecord<byte[], byte[]> serialize(VadResult element, KafkaRecordSerializationSchema.KafkaSinkContext context, @Nullable Long timestamp) {
    try {
      if (mapper == null) {
        mapper = new ObjectMapper();
      }
      
      byte[] key = element.getSessionId() != null ? element.getSessionId().getBytes() : null;
      byte[] value = mapper.writeValueAsBytes(element);
      
      return new ProducerRecord<>(targetTopic, key, value);
    } catch (Exception e) {
      throw new RuntimeException("Error serializing VadResult", e);
    }
  }
}

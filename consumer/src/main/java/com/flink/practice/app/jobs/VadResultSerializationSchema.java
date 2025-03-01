package com.flink.practice.app.jobs;

import org.apache.flink.streaming.util.serialization.KeyedSerializationSchema;
import com.fasterxml.jackson.databind.ObjectMapper;

public class VadResultSerializationSchema implements KeyedSerializationSchema<VadResult> {

  private final String targetTopic;
  private transient ObjectMapper mapper;

  public VadResultSerializationSchema(String targetTopic) {
    this.targetTopic = targetTopic;
  }

  @Override
  public byte[] serializeKey(VadResult element) {
    return element.getSessionId() != null ? element.getSessionId().getBytes() : null;
  }

  @Override
  public byte[] serializeValue(VadResult element) {
    try {
      if (mapper == null) {
        mapper = new ObjectMapper();
      }
      return mapper.writeValueAsBytes(element);
    } catch (Exception e) {
      throw new RuntimeException("Error serializing VadResult", e);
    }
  }

  @Override
  public String getTargetTopic(VadResult element) {
    return targetTopic;
  }
}

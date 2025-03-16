package com.flink.practice.app.jobs;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema.KafkaSinkContext;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

public class STTResultSerializationSchema implements KafkaRecordSerializationSchema<STTResult> {
    private final String topic;
    
    public STTResultSerializationSchema(String topic) {
        this.topic = topic;
    }
    
    @Override
    public ProducerRecord<byte[], byte[]> serialize(STTResult result, KafkaSinkContext context, Long timestamp) {
        if (result == null) {
            return null;
        }
        
        JSONObject json = new JSONObject();
        json.put("sessionId", result.getSessionId());
        json.put("text", result.getText());
        json.put("startTime", result.getStartTime());
        json.put("endTime", result.getEndTime());
        json.put("forced", result.isForced());
        
        byte[] value = json.toString().getBytes(StandardCharsets.UTF_8);
        return new ProducerRecord<>(topic, result.getSessionId().getBytes(StandardCharsets.UTF_8), value);
    }
} 
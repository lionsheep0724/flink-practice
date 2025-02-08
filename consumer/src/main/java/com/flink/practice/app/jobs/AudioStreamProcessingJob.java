package com.flink.practice.app.jobs;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import java.util.Properties;

import com.flink.practice.app.jobs.AudioChunk;

public class AudioStreamProcessingJob {
  public static void main(String[] args) throws Exception {
    final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.enableCheckpointing(10000);

    // Kafka 소비자 설정
    Properties properties = new Properties();
    properties.setProperty("bootstrap.servers", "localhost:9092"); // 실제 Kafka 브로커 주소
    properties.setProperty("group.id", "audio-group");

    // Kafka 소스 생성: 키와 값 모두를 AudioChunk로 변환
    FlinkKafkaConsumer<AudioChunk> kafkaConsumer = new FlinkKafkaConsumer<>(
        "audio-packet-topic", // Kafka 토픽
        new AudioChunkKeyedDeserializationSchema(),
        properties
    );

    // Kafka 소스에서 읽은 AudioChunk는 이미 sessionId가 포함되어 있으므로 바로 keyBy 가능
    env.addSource(kafkaConsumer)
        .keyBy(AudioChunk::getSessionId)
        .process(new AudioKeyedProcessFunction())
        .print();


    env.execute("Audio Stream Processing with Keyed State");
  }
}

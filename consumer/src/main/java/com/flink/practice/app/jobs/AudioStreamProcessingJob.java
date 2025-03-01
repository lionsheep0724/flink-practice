package com.flink.practice.app.jobs;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer;

import java.util.Properties;

public class AudioStreamProcessingJob {
  public static void main(String[] args) throws Exception {
    final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.enableCheckpointing(10000);

    // Kafka 설정
    Properties properties = new Properties();
    properties.setProperty("bootstrap.servers", "localhost:9092");
    properties.setProperty("group.id", "audio-group");

    // Kafka Consumer: audio-packet-topic에서 AudioChunk를 소비
    FlinkKafkaConsumer<AudioChunk> kafkaConsumer = new FlinkKafkaConsumer<>(
        "audio-packet-topic",
        new AudioChunkKeyedDeserializationSchema(),
        properties
    );

    // Kafka Producer: vad-result-topic에 VadResult를 게시 (at-least-once semantics)
    FlinkKafkaProducer<VadResult> kafkaProducer = new FlinkKafkaProducer<>(
        "vad-result-topic",
        new VadResultSerializationSchema("vad-result-topic"),
        properties,
        FlinkKafkaProducer.Semantic.AT_LEAST_ONCE
    );

    env.addSource(kafkaConsumer)
        .keyBy(AudioChunk::getSessionId)
        .process(new AudioKeyedProcessFunction())
        .addSink(kafkaProducer);

    env.execute("Audio Stream Processing with VAD and Kafka Sink");
  }
}

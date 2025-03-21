package com.flink.practice.app.jobs;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.sink.KafkaSinkBuilder;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.Properties;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;

@Service
public class AudioStreamProcessingJob {
  // Kafka 설정
  @Value("${kafka.bootstrap.servers:kafka:9092}")
  private String bootstrapServers;

  // Triton 서버 설정
  private static final String TRITON_SERVER_URL = System.getProperty("triton.server.url", "vad-triton");
  private static final int TRITON_SERVER_PORT = Integer.parseInt(System.getProperty("triton.server.port", "8000"));
  private static final String TRITON_MODEL_NAME = System.getProperty("triton.model.name", "vad_model");
  private static final String TRITON_MODEL_VERSION = System.getProperty("triton.model.version", "1");
  private static final int SAMPLING_RATE = Integer.parseInt(System.getProperty("audio.sampling.rate", "16000"));
  
  // 버퍼링 설정
  private static final int PROCESSING_INTERVAL_MS = Integer.parseInt(System.getProperty("audio.processing.interval.ms", "1000"));
  private static final int BUFFER_OVERLAP_MS = Integer.parseInt(System.getProperty("audio.buffer.overlap.ms", "500"));

  public static void submitJob() {
    try {
      final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
      env.enableCheckpointing(10000);
      env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
      env.getCheckpointConfig().setMinPauseBetweenCheckpoints(5000);
      env.getCheckpointConfig().setCheckpointTimeout(60000);

      // Kafka 설정
      String bootstrapServers = System.getProperty("kafka.bootstrap.servers", "kafka:9092");
      String consumerGroupId = System.getProperty("kafka.consumer.group.id", "audio-group");
      
      // 입력 토픽 설정
      String inputTopic = System.getProperty("kafka.input.topic", "audio-packet-topic");
      String outputTopic = System.getProperty("kafka.output.topic", "stt-result-topic");

      // 버퍼 오버랩 계산 (ms -> bytes)
      // 16비트 오디오 = 샘플당 2바이트
      int bufferOverlapInBytes = (SAMPLING_RATE * BUFFER_OVERLAP_MS / 1000) * 2;

      // Kafka Source 설정
      KafkaSource<AudioChunk> kafkaSource = KafkaSource.<AudioChunk>builder()
          .setBootstrapServers(bootstrapServers)
          .setTopics(inputTopic)
          .setGroupId(consumerGroupId)
          .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.LATEST))
          .setDeserializer(KafkaRecordDeserializationSchema.of(new AudioChunkKeyedDeserializationSchema()))
          .build();

      // Kafka Sink 설정 - STTResult 직렬화 스키마로 변경
      KafkaSink<STTResult> kafkaSink = KafkaSink.<STTResult>builder()
          .setBootstrapServers(bootstrapServers)
          .setRecordSerializer(new STTResultSerializationSchema(outputTopic))
          .build();

      // Triton 기반 VAD 처리 파이프라인 구성
      env.fromSource(kafkaSource, WatermarkStrategy.noWatermarks(), "Kafka Source")
          .keyBy(AudioChunk::getSessionId)
          .process(new AudioKeyedProcessFunction(
              TRITON_SERVER_URL,
              TRITON_SERVER_PORT,
              TRITON_MODEL_NAME,
              TRITON_MODEL_VERSION,
              SAMPLING_RATE,
              bufferOverlapInBytes
          ))
          .sinkTo(kafkaSink);

      env.execute("Audio Stream Processing with Triton VAD and STT");
    } catch( Exception e ){
      e.printStackTrace();
      throw new RuntimeException("Failed to start Flink job", e);
    }
  }
}
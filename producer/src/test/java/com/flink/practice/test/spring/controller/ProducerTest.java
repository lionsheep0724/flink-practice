package com.flink.practice.test.spring.controller;

import com.flink.practice.app.AudioStreamingApplication;
import com.flink.practice.test.util.AudioDataGenerator;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = AudioStreamingApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@EmbeddedKafka(partitions = 1, topics = {"audio-packet-topic"})
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
    "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer"
})
public class ProducerTest {

    private static final String TOPIC = "audio-packet-topic";

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka; // 임베디드 카프카 브로커

    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate;

    @BeforeEach
    void setUp() {
        // 필요 시 초기화
    }

    @Test
    void testProducerRecordWithRandomAudio() {
        String sessionId = "test-session-random";

        // 첫 번째 청크
        byte[] firstChunk = AudioDataGenerator.generateRandomAudioChunk(5120);
        ProducerRecord<String, byte[]> record1 =
            new ProducerRecord<>(TOPIC, sessionId, firstChunk);
        record1.headers().add("isFirst", "true".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record1);

        // 두 번째 청크
        byte[] secondChunk = AudioDataGenerator.generateRandomAudioChunk(5120);
        ProducerRecord<String, byte[]> record2 =
            new ProducerRecord<>(TOPIC, sessionId, secondChunk);
        record2.headers().add("isFirst", "false".getBytes(StandardCharsets.UTF_8));
        record2.headers().add("isLast", "false".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record2);

        // 세 번째 청크
        byte[] thirdChunk = AudioDataGenerator.generateRandomAudioChunk(5120);
        ProducerRecord<String, byte[]> record3 =
            new ProducerRecord<>(TOPIC, sessionId, thirdChunk);
        record3.headers().add("isLast", "true".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record3);

        // 직접 Consumer 생성
        Map<String, Object> consumerProps =
            KafkaTestUtils.consumerProps("my-test-group", "true", embeddedKafka);

        // 필요한 ConsumerConfig 설정 (AUTO_OFFSET_RESET 등)
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (Consumer<String, byte[]> consumer = new KafkaConsumer<>(
                consumerProps,
                new StringDeserializer(),
                new ByteArrayDeserializer()
        )) {
            consumer.subscribe(Collections.singletonList(TOPIC));

            // 메시지 폴링 (최대 5초)
            ConsumerRecords<String, byte[]> records =
                consumer.poll(Duration.ofSeconds(5));

            // 예: 수동 커밋 / 소프트 체크 가능
            // consumer.commitSync();

            assertThat(records.count()).isEqualTo(3);

            boolean foundFirst = false;
            boolean foundLast = false;

            for (ConsumerRecord<String, byte[]> rec : records) {
                if (rec.headers().lastHeader("isFirst") != null) {
                    foundFirst = true;
                }
                if (rec.headers().lastHeader("isLast") != null) {
                    foundLast = true;
                }
            }
            assertThat(foundFirst).isTrue();
            assertThat(foundLast).isTrue();
        }
    }
}

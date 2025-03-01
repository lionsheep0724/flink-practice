package com.flink.practice.app.vad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.Random;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

@EmbeddedKafka(partitions = 1, topics = { "audio-packet-topic" })
public class AudioConsumeTest {

    @Test
    public void testAudioPacketConsumption(EmbeddedKafkaBroker embeddedKafka) {
        // Producer 설정
        Properties producerProps = new Properties();
        producerProps.put("bootstrap.servers", embeddedKafka.getBrokersAsString());
        producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producerProps.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);

        // 테스트용 오디오 데이터 생성: 랜덤하게 5120바이트 생성
        String sessionId = "test-session";
        byte[] audioData = new byte[5120];
        new Random().nextBytes(audioData);

        // 메시지 게시: key는 sessionId, value는 audioData
        ProducerRecord<String, byte[]> record =
                new ProducerRecord<>("audio-packet-topic", sessionId, audioData);
        producer.send(record);
        producer.close();

        // Consumer 설정
        Properties consumerProps = new Properties();
        consumerProps.put("bootstrap.servers", embeddedKafka.getBrokersAsString());
        consumerProps.put("group.id", "test-group");
        consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        consumerProps.put("auto.offset.reset", "earliest");
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList("audio-packet-topic"));

        // 메시지 폴링: 10초 동안 폴링하여 메시지 수신
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofSeconds(10));
        consumer.close();

        // 결과 검증 및 출력
        assertNotNull(records, "ConsumerRecords should not be null");
        int count = records.count();
        assertEquals(1, count, "Should have received exactly one record");

        for (ConsumerRecord<String, byte[]> rec : records) {
            // 키와 값 길이 출력
            System.out.println("Consumed record: key = " + rec.key() +
                    ", value length = " + rec.value().length + " bytes");
            // 선택적으로, 값의 처음 20 바이트를 헥스 문자열로 출력
            byte[] value = rec.value();
            StringBuilder hexBuilder = new StringBuilder();
            for (int i = 0; i < Math.min(20, value.length); i++) {
                hexBuilder.append(String.format("%02X ", value[i]));
            }
            System.out.println("First 20 bytes: " + hexBuilder.toString());
        }
    }
}

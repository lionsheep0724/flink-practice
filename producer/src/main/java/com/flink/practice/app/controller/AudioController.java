package com.flink.practice.app.controller;

import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;

@RestController
public class AudioController {

    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    public AudioController(KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping(value = "/stream", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Mono<String> consumeAudio(ServerHttpRequest request) {

        // 고유한 sessionId 발급
        String sessionId = UUID.randomUUID().toString();
        System.out.println("Issued sessionId: " + sessionId);

        // 누적 버퍼 및 '첫번째 청크 전송 여부' 표시
        ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
        final boolean[] hasSentFirstChunk = { false };

        // 요청 본문(Flux<DataBuffer>) 처리
        return request.getBody()
            .doOnNext(dataBuffer -> {
                try {
                    // 읽은 바이트를 누적
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    accumulator.write(bytes);

                    // 5120 바이트 이상 누적되면 청크 단위로 전송
                    while (accumulator.size() >= 5120) {
                        byte[] fullBuffer = accumulator.toByteArray();
                        byte[] chunkToPublish = Arrays.copyOfRange(fullBuffer, 0, 5120);

                        // ProducerRecord 생성
                        ProducerRecord<String, byte[]> record =
                            new ProducerRecord<>("audio-packet-topic", sessionId, chunkToPublish);

                        // 첫번째 청크라면 헤더에 isFirst=true
                        if (!hasSentFirstChunk[0]) {
                            record.headers().add("isFirst", "true".getBytes());
                            hasSentFirstChunk[0] = true;
                        }
                        // 중간 청크이므로 isLast=false (아예 추가 안 해도 됨)

                        // 전송
                        kafkaTemplate.send(record);
                        System.out.printf("Published chunk (size=%d) with sessionId=%s, isFirst=%b%n",
                                chunkToPublish.length, sessionId, !hasSentFirstChunk[0]);

                        // 전송한 부분 제외하고 leftover 처리
                        int remaining = accumulator.size() - 5120;
                        byte[] remainder = Arrays.copyOfRange(fullBuffer, 5120, fullBuffer.length);
                        accumulator.reset();
                        accumulator.write(remainder);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Error processing audio data", e);
                }
            })
            // 스트림이 완료되면 leftover가 존재할 수 있으므로 마지막 청크 전송
            .doOnComplete(() -> {
                byte[] leftover = accumulator.toByteArray();
                if (leftover.length > 0) {
                    // leftover가 첫 청크이자 마지막일 수도 있음
                    ProducerRecord<String, byte[]> record =
                        new ProducerRecord<>("audio-packet-topic", sessionId, leftover);

                    // 첫 청크 전송된 적 없다면 isFirst=true
                    if (!hasSentFirstChunk[0]) {
                        record.headers().add("isFirst", "true".getBytes());
                        hasSentFirstChunk[0] = true; // 의미상 업데이트
                    }
                    // 마지막 청크 표시
                    record.headers().add("isLast", "true".getBytes());

                    kafkaTemplate.send(record);
                    System.out.printf("Published leftover chunk (size=%d) with sessionId=%s, isFirst=%b, isLast=true%n",
                            leftover.length, sessionId, !hasSentFirstChunk[0]);
                } else {
                    // leftover가 없지만 '마지막'임을 알리고 싶다면, 빈 메시지나 별도 신호를 보내도 됨
                    // 여기서는 생략
                }
            })
            .then(Mono.just("OK"));
    }
}

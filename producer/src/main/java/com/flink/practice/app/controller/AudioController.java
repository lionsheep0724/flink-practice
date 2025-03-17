package com.flink.practice.app.controller;

import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.json.JSONObject;

@RestController
public class AudioController {

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    // 세션 ID와 SSE 싱크를 매핑하기 위한 맵
    private final Map<String, Sinks.Many<String>> sttResultSinks = new ConcurrentHashMap<>();

    public AudioController(KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping(value = "/stream", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Flux<DataBuffer> consumeAudio(ServerHttpRequest request, ServerHttpResponse response) {
        // 고유한 sessionId 발급
        String sessionId = UUID.randomUUID().toString();
        System.out.println("Issued sessionId: " + sessionId);

        // 응답 헤더 설정
        response.getHeaders().set("Transfer-Encoding", "chunked");
        response.getHeaders().setContentType(MediaType.TEXT_PLAIN);

        // 이 세션을 위한 Sink 생성
        Sinks.Many<String> sttResultSink = Sinks.many().multicast().onBackpressureBuffer();
        sttResultSinks.put(sessionId, sttResultSink);

        // 데이터버퍼 팩토리 (응답 생성용)
        DataBufferFactory bufferFactory = response.bufferFactory();

        // 누적 버퍼 및 '첫번째 청크 전송 여부' 표시
        ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
        final boolean[] hasSentFirstChunk = { false };

        // 오디오 데이터 처리 파이프라인 비동기로 시작
        request.getBody()
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
                    // 마지막 빈 청크 전송 (종료 신호용)
                    ProducerRecord<String, byte[]> record =
                        new ProducerRecord<>("audio-packet-topic", sessionId, new byte[0]);
                    record.headers().add("isLast", "true".getBytes());
                    kafkaTemplate.send(record);
                    System.out.println("Published empty final chunk with isLast=true");
                }
                
                // 일정 시간 후 리소스 정리
                Mono.delay(java.time.Duration.ofMinutes(5))
                    .subscribe(unused -> {
                        sttResultSinks.remove(sessionId);
                        System.out.println("세션 리소스 정리 완료: " + sessionId);
                    });
            })
            .subscribe(); // 비동기 처리 시작
        
        // 응답 스트림 생성 및 즉시 반환
        return Flux.concat(
            // 세션 ID 전송 (즉시)
            Mono.just(bufferFactory.wrap(("SESSION:" + sessionId + "\n").getBytes(StandardCharsets.UTF_8))),
            
            // STT 결과 스트리밍 (결과 생성되는 대로 실시간)
            sttResultSink.asFlux()
                .map(result -> bufferFactory.wrap((result + "\n").getBytes(StandardCharsets.UTF_8)))
        );
    }

    // Kafka 리스너로 STT 결과 수신
    @KafkaListener(topics = "stt-result-topic", groupId = "stt-result-group")
    public void consumeSttResult(String message) {
        try {
            // JSON 메시지 파싱
            JSONObject resultJson = new JSONObject(message);
            String sessionId = resultJson.getString("sessionId");
            String text = resultJson.getString("text");
            
            Sinks.Many<String> sink = sttResultSinks.get(sessionId);
            if (sink != null) {
                // text 필드만 전송
                sink.tryEmitNext(text);
                System.out.println("STT 결과 텍스트를 클라이언트로 전달. 세션 ID: " + sessionId + ", 텍스트: " + text);
            } else {
                System.out.println("활성 Sink를 찾을 수 없음. 세션 ID: " + sessionId);
            }
        } catch (Exception e) {
            System.err.println("STT 결과 처리 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

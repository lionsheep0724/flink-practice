package com.flink.practice.app.controller;

import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

@RestController
public class AudioController {

  // KafkaTemplate를 통해 Kafka에 메시지를 전송 (키는 필요에 따라 지정할 수 있음)
  private final KafkaTemplate<String, byte[]> kafkaTemplate;

  // 생성자 주입
  public AudioController(KafkaTemplate<String, byte[]> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  @PostMapping(
      value = "/stream",
      consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE
  )
  public Mono<String> consumeAudio(ServerHttpRequest request) {
    // 각 HTTP 요청마다 누적 버퍼를 생성 (Python의 bytearray와 유사)
    ByteArrayOutputStream accumulator = new ByteArrayOutputStream();

    // 요청 본문에서 오디오 데이터를 Flux<DataBuffer>로 읽습니다.
    return request.getBody()
        .doOnNext(dataBuffer -> {
          try {
            // DataBuffer에서 바이트 배열 추출
            byte[] bytes = new byte[dataBuffer.readableByteCount()];
            dataBuffer.read(bytes);

            // 누적 버퍼에 추가
            accumulator.write(bytes);

            // 누적된 데이터가 5120바이트 이상이면,
            // 5120바이트 단위로 잘라서 Kafka에 게시합니다.
            while (accumulator.size() >= 5120) {
              byte[] fullBuffer = accumulator.toByteArray();
              // 첫 5120바이트를 추출
              byte[] chunkToPublish = Arrays.copyOfRange(fullBuffer, 0, 5120);

              // Kafka에 메시지 전송 (예: "audio-topic" 토픽에 전송)
              kafkaTemplate.send("audio-packet-topic", chunkToPublish);
              System.out.println("Published a Kafka message with 5120 bytes.");

              // 전송한 부분을 누적 버퍼에서 제거
              int remaining = accumulator.size() - 5120;
              byte[] remainder = Arrays.copyOfRange(fullBuffer, 5120, fullBuffer.length);
              accumulator.reset();
              accumulator.write(remainder);
            }
          } catch (IOException e) {
            throw new RuntimeException("Error processing audio data", e);
          }
        })
        .then(Mono.just("OK")); // 스트림 처리가 모두 완료되면 OK 응답 반환
  }
}

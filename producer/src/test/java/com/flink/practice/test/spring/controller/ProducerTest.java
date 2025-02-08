package com.flink.practice.test.spring.controller;

import com.flink.practice.app.AudioStreamingApplication;
import com.flink.practice.test.util.AudioDataGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AudioStreamingApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class ProducerTest {

  @LocalServerPort
  private int port;  // 실제 할당된 포트가 주입됩니다.

  private WebClient webClient;

  @BeforeEach
  void setUp() {
    webClient = WebClient.builder()
        .baseUrl("http://localhost:" + port) // 주입된 포트를 사용
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
        .defaultHeader(HttpHeaders.TRANSFER_ENCODING, "chunked")
        .build();
  }

  @Test
  void testStreamingAudioChunks() {
    Flux<byte[]> audioStream = Flux.interval(Duration.ofMillis(320))
        .map(i -> AudioDataGenerator.generateRandomAudioChunk())
        .take(10);

    Mono<String> response = webClient.post()
        .uri("/stream")
        .body(BodyInserters.fromPublisher(audioStream, byte[].class))
        .retrieve()
        .bodyToMono(String.class);

    String result = response.block();  // 한 번만 호출
    System.out.println("Response: " + result);
    assertThat(result).isEqualTo("OK");
  }
}

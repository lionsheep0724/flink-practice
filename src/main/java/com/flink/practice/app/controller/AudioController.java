package com.flink.practice.app.controller;

import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

@RestController
public class AudioController {

  @PostMapping(
      value = "/stream",
      consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE
  )
  public Mono<String> consumeAudio(ServerHttpRequest request) {
    // body() 대신 getBody()를 쓰는 이유:
    //   - WebFlux의 ServerHttpRequest.getBody()는 Flux<DataBuffer>를 반환
    //   - 오디오 스트림(바이너리 데이터)을 논블로킹으로 받아볼 수 있음
    Flux<DataBuffer> audioFlux = request.getBody();

    return audioFlux
        .doOnNext(dataBuffer -> {
          // 1) DataBuffer에서 byte[] 추출
          byte[] audioBytes = new byte[dataBuffer.readableByteCount()];
          dataBuffer.read(audioBytes);

          // 2) 여기서는 단순히 데이터 길이만 로깅
          System.out.println("Received audio chunk. size=" + audioBytes.length + " bytes");

          // 3) 추후 Flink나 Kafka로 보낼 로직을 넣으면 됨
        })
        .then(Mono.just("OK")); // 모든 스트림 처리가 끝나면 OK 문자열 응답
  }
}

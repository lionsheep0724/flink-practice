package com.flink.practice.e2etest;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;

public class ClientProducerIntegrationTest {

    @Test
    public void testClientProducerIntegrationTest() throws IOException {
        // 테스트용 .wav 파일 읽기 (파일 경로를 실제 파일 위치에 맞게 조정)
        byte[] wavBytes = Files.readAllBytes(Paths.get("src/test/resources/test.wav"));
        
        // 5120바이트 청크로 분할
        int chunkSize = 5120;
        int totalChunks = (int) Math.ceil((double) wavBytes.length / chunkSize);

        // DataBufferFactory를 사용하여 byte 배열을 DataBuffer로 감쌉니다.
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        Flux<DataBuffer> dataBufferFlux = Flux.range(0, totalChunks)
        .map(i -> {
            int start = i * chunkSize;
            int end = Math.min(wavBytes.length, (i + 1) * chunkSize);
            byte[] chunk = new byte[end - start];
            System.arraycopy(wavBytes, start, chunk, 0, end - start);
            return factory.wrap(chunk);
        })
        .delayElements(Duration.ofMillis(320))
        .map(db -> (DataBuffer) db);


        // producer 서버에 요청 (docker-compose에서 producer는 호스트의 8081 포트에 매핑됨)
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:25250")
                .build();

        // POST 요청 전송, 헤더에 transfer-encoding: chunked 포함
        Flux<String> responseFlux = client.post()
                .uri("/stream")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Transfer-Encoding", "chunked")
                .body(dataBufferFlux, DataBuffer.class)
                .retrieve()
                .bodyToFlux(String.class);

        // 응답이 "OK"임을 검증 (producer 서버에서 OK 응답을 반환하도록 구현되어 있다고 가정)
        StepVerifier.create(responseFlux)
                .expectNext("OK")
                .verifyComplete();
    }
}

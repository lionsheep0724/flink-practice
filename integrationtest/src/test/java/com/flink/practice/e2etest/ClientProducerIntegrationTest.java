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
    public void testClientProducerIntegrationTest() throws IOException, InterruptedException {
        // 테스트용 .wav 파일 읽기 (파일 경로를 실제 파일 위치에 맞게 조정)
        byte[] wavBytes = Files.readAllBytes(Paths.get("src/test/resources/test.wav"));
        
        System.out.println("WAV 파일 크기: " + wavBytes.length + " 바이트");
        
        // 5120바이트 청크로 분할
        int chunkSize = 5120;
        int totalChunks = (int) Math.ceil((double) wavBytes.length / chunkSize);
        System.out.println("총 청크 수: " + totalChunks);

        // DataBufferFactory를 사용하여 byte 배열을 DataBuffer로 감쌉니다.
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        Flux<DataBuffer> dataBufferFlux = Flux.range(0, totalChunks)
            .map(i -> {
                int start = i * chunkSize;
                int end = Math.min(wavBytes.length, (i + 1) * chunkSize);
                byte[] chunk = new byte[end - start];
                System.arraycopy(wavBytes, start, chunk, 0, end - start);
                System.out.println("청크 " + (i+1) + "/" + totalChunks + " 생성: " + chunk.length + " 바이트");
                // 여기서 바로 DataBuffer 타입으로 반환
                return (DataBuffer)factory.wrap(chunk);
            })
            .delayElements(Duration.ofMillis(320));

        // producer 서버에 요청 
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:25250")  // 실제 서버 주소 확인
                .build();

        System.out.println("WebClient 생성 완료, 요청 시작...");

        // POST 요청 전송, 헤더에 transfer-encoding: chunked 포함
        Flux<String> responseFlux = client.post()
                .uri("/stream")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Transfer-Encoding", "chunked")
                .body(dataBufferFlux, DataBuffer.class)
                .retrieve()
                .bodyToFlux(String.class);

        // 중요: 응답을 구독해야 실제 요청이 실행됨
        responseFlux.subscribe(
            response -> System.out.println("응답 수신: " + response),
            error -> System.err.println("오류 발생: " + error),
            () -> System.out.println("응답 스트림 완료")
        );
        
        // 테스트가 너무 빨리 끝나지 않도록 충분한 시간 기다림
        System.out.println("응답 대기 중...");
        Thread.sleep(10000); // 10초 대기
        System.out.println("테스트 완료");
    }
}

package com.flink.practice.test;

import com.flink.practice.app.jobs.TritonVadClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Random;

public class TritonVadClientTest {

    private ObjectMapper objectMapper;
    
    // 테스트 매개변수
    private final String TEST_SERVER_URL = "localhost";
    private final int TEST_SERVER_PORT = 8000;
    private final String TEST_MODEL = "vad_model";
    private final String TEST_VERSION = "1";
    private final int TEST_SAMPLING_RATE = 16000;
    
    private TritonVadClient vadClient;
    
    @BeforeEach
    public void setup() {
        objectMapper = new ObjectMapper();
        // 실제 배포된 vad 서버에 요청하도록 TritonVadClient 인스턴스를 생성합니다.
        vadClient = new TritonVadClient(
            TEST_SERVER_URL,
            TEST_SERVER_PORT,
            TEST_MODEL,
            TEST_VERSION,
            TEST_SAMPLING_RATE
        );
    }
    
    @Test
    public void testProcessAudioChunk_With512ByteChunk() throws IOException {
        // 1. 테스트 오디오 데이터 생성 (512 바이트)
        byte[] testAudio = new byte[1024];
        new Random().nextBytes(testAudio);
        
        // 2. 실제 vad 서버에 요청 보내기
        System.out.println("Send request for VadClientTest");
        String responseJson = vadClient.processAudioChunk(testAudio,true,false);
        System.out.println("Response JSON (512 bytes): " + responseJson);
        assertNotNull(responseJson, "응답 JSON은 null이 아니어야 합니다");
        
        // 3. JSON 응답 파싱 및 간단한 검증 (서버 응답에 따라 검증 내용을 추가하세요)
        JsonNode rootNode = objectMapper.readTree(responseJson);
        JsonNode outputs = rootNode.get("outputs");
        assertNotNull(outputs, "outputs 필드는 존재해야 합니다");
        assertTrue(outputs.isArray(), "outputs는 배열이어야 합니다");
        // 예시로, outputs 배열의 크기가 2여야 한다고 가정 (EVENT와 TIMESTAMP)
        assertEquals(2, outputs.size(), "outputs 배열의 크기는 2여야 합니다");
    }
    
    
    @Test
    public void testReset() {
        // 세션 초기화를 위해 reset() 호출
        vadClient.reset();
        
        byte[] testAudio = new byte[1024];
        new Random().nextBytes(testAudio);
        
        try {
            String responseJson = vadClient.processAudioChunk(testAudio,true,false);
            System.out.println("Response JSON after reset: " + responseJson);
            assertNotNull(responseJson, "리셋 후 응답 JSON은 null이 아니어야 합니다");
        } catch (Exception e) {
            fail("리셋 후 예외가 발생하면 안됩니다: " + e.getMessage());
        }
    }
}

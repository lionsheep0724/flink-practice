package com.flink.practice.test;

import com.flink.practice.app.jobs.TritonVadClient;
import com.flink.practice.app.jobs.AudioChunk;
import com.flink.practice.app.jobs.VadResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.Map;
import java.util.Random;

@ExtendWith(MockitoExtension.class)
public class TritonVadClientTest {
    
    @Mock
    private CloseableHttpClient httpClient;
    
    @Mock
    private CloseableHttpResponse httpResponse;
    
    @Mock
    private StatusLine statusLine;
    
    @Mock
    private HttpEntity httpEntity;
    
    private ObjectMapper objectMapper;
    
    // 테스트 매개변수
    private final String TEST_SERVER_URL = "localhost";
    private final int TEST_SERVER_PORT = 8000;
    private final String TEST_MODEL = "silero_vad";
    private final String TEST_VERSION = "1";
    private final int TEST_SAMPLING_RATE = 16000;
    
    private TritonVadClient vadClient;
    
    @BeforeEach
    public void setup() throws Exception {
        objectMapper = new ObjectMapper();
        
        // HTTP 응답 모킹 설정
        when(statusLine.getStatusCode()).thenReturn(HttpStatus.SC_OK);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        
        // 응답 엔티티 설정
        String mockResponseJson = createMockResponseJson();
        when(httpEntity.getContent()).thenReturn(new ByteArrayInputStream(mockResponseJson.getBytes()));
        when(httpResponse.getEntity()).thenReturn(httpEntity);
        
        // HTTP 클라이언트가 POST 요청에 대해 모의 응답을 반환하도록 설정
        when(httpClient.execute(any(HttpPost.class))).thenReturn(httpResponse);
        
        // TritonVadClient 생성 및 모킹된 HTTP 클라이언트 주입
        vadClient = new TritonVadClient(
            TEST_SERVER_URL,
            TEST_SERVER_PORT,
            TEST_MODEL,
            TEST_VERSION,
            TEST_SAMPLING_RATE
        );
        
        // 리플렉션을 사용하여 private 필드 설정
        java.lang.reflect.Field httpClientField = TritonVadClient.class.getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        httpClientField.set(vadClient, httpClient);
        
        java.lang.reflect.Field objectMapperField = TritonVadClient.class.getDeclaredField("objectMapper");
        objectMapperField.setAccessible(true);
        objectMapperField.set(vadClient, objectMapper);
    }
    
    /**
     * Triton 서버의 모의 JSON 응답 생성
     */
    private String createMockResponseJson() throws IOException {
        ObjectNode responseNode = objectMapper.createObjectNode();
        ArrayNode outputsArray = objectMapper.createArrayNode();
        
        // 1. 음성 확률 출력
        ObjectNode outputNode = objectMapper.createObjectNode();
        outputNode.put("name", "output");
        outputNode.put("datatype", "FP32");
        ArrayNode outputShape = objectMapper.createArrayNode();
        outputShape.add(1);
        outputNode.set("shape", outputShape);
        
        ArrayNode outputData = objectMapper.createArrayNode();
        outputData.add(0.7f); // 음성 감지 확률
        outputNode.set("data", outputData);
        outputsArray.add(outputNode);
        
        // 2. h 상태 출력
        ObjectNode hOutputNode = objectMapper.createObjectNode();
        hOutputNode.put("name", "h_out");
        hOutputNode.put("datatype", "FP32");
        ArrayNode hShape = objectMapper.createArrayNode();
        hShape.add(1);
        hShape.add(128);
        hOutputNode.set("shape", hShape);
        
        ArrayNode hData = objectMapper.createArrayNode();
        for (int i = 0; i < 128; i++) {
            hData.add(0.0f);
        }
        hOutputNode.set("data", hData);
        outputsArray.add(hOutputNode);
        
        // 3. c 상태 출력
        ObjectNode cOutputNode = objectMapper.createObjectNode();
        cOutputNode.put("name", "c_out");
        cOutputNode.put("datatype", "FP32");
        ArrayNode cShape = objectMapper.createArrayNode();
        cShape.add(1);
        cShape.add(128);
        cOutputNode.set("shape", cShape);
        
        ArrayNode cData = objectMapper.createArrayNode();
        for (int i = 0; i < 128; i++) {
            cData.add(0.0f);
        }
        cOutputNode.set("data", cData);
        outputsArray.add(cOutputNode);
        
        // 최종 응답 구성
        responseNode.set("outputs", outputsArray);
        
        return objectMapper.writeValueAsString(responseNode);
    }
    
    @Test
    public void testProcessAudioChunk_With512ByteChunks() throws IOException {
        // 1. 테스트 오디오 데이터 생성 (512 바이트)
        byte[] testAudio = new byte[512];
        new Random().nextBytes(testAudio);
        
        // 2. 오디오 청크 처리 테스트
        Map<String, Double> result = vadClient.processAudioChunk(testAudio);
        
        // 3. 결과 검증
        assertNotNull(result, "결과는 null이 아니어야 합니다");
        // 결과가 비어있지 않거나 null이 아닌지 확인
        assertFalse(result == null || result.isEmpty(), "결과는 비어있지 않아야 합니다");
        
        // 4. HTTP 클라이언트가 호출되었는지 검증
        verify(httpClient, times(1)).execute(any(HttpPost.class));
    }
    
    @Test
    public void testProcessAudioChunk_WithLargerChunks() throws IOException {
        // 1. 테스트 오디오 데이터 생성 (2048 바이트)
        byte[] testAudio = new byte[2048];
        new Random().nextBytes(testAudio);
        
        // 2. 오디오 청크 처리 테스트
        Map<String, Double> result = vadClient.processAudioChunk(testAudio);
        
        // 3. 결과 검증
        assertNotNull(result, "결과는 null이 아니어야 합니다");
        // 결과는 비어있을 수 있음 - 시작 또는 종료 이벤트가 발생하지 않았을 수 있음
        // 중요한 것은 processAudioChunk가 예외 없이 실행되는 것
        
        // 4. HTTP 클라이언트가 호출되었는지 검증 - 512바이트 청크로 여러 번 호출
        verify(httpClient, atLeast(1)).execute(any(HttpPost.class));
    }
    
    @Test
    public void testReset() {
        // 상태 초기화 테스트
        vadClient.reset();
        
        // 리셋 후에도 동작 테스트
        byte[] testAudio = new byte[512];
        new Random().nextBytes(testAudio);
        
        try {
            Map<String, Double> result = vadClient.processAudioChunk(testAudio);
            assertNotNull(result, "리셋 후에도 결과는 null이 아니어야 합니다");
        } catch (Exception e) {
            fail("리셋 후 예외가 발생하면 안됩니다: " + e.getMessage());
        }
    }
} 
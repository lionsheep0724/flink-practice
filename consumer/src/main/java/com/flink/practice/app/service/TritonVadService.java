package com.flink.practice.app.service;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triton Inference Server를 통해 VAD(Voice Activity Detection) 처리를 수행하는 서비스
 * HTTP REST API 사용
 */
@Service
public class TritonVadService {
    private static final Logger LOG = LoggerFactory.getLogger(TritonVadService.class);

    @Value("${triton.server.url:localhost}")
    private String tritonServerUrl;

    @Value("${triton.server.port:8000}")
    private int tritonServerPort;

    @Value("${triton.model.name:silero_vad}")
    private String modelName;

    @Value("${triton.model.version:1}")
    private String modelVersion;

    private CloseableHttpClient httpClient;
    private ObjectMapper objectMapper;

    // 세션별 상태 관리 (시작 상태, RNN 상태 등)
    private final ConcurrentMap<String, Map<String, Object>> sessionStates = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // HTTP 클라이언트 초기화
        httpClient = HttpClients.createDefault();
        objectMapper = new ObjectMapper();
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (httpClient != null) {
                httpClient.close();
            }
        } catch (IOException e) {
            LOG.error("HTTP 클라이언트 종료 중 오류 발생", e);
        }
    }

    /**
     * 오디오 패킷을 Triton Server로 전송하여 VAD 결과를 얻습니다.
     *
     * @param sessionId 세션 ID
     * @param audioData 오디오 데이터 (16비트 PCM)
     * @param sampleRate 샘플링 레이트 (8000 또는 16000)
     * @return VAD 결과 (start/end 타임스탬프를 포함한 맵)
     */
    public Map<String, Double> processAudioChunk(String sessionId, byte[] audioData, int sampleRate) {
        try {
            // 세션 상태 가져오기 (없으면 생성)
            Map<String, Object> sessionState = sessionStates.computeIfAbsent(sessionId, id -> {
                Map<String, Object> newState = new HashMap<>();
                newState.put("triggered", false);
                newState.put("currentSample", 0);
                newState.put("tempEnd", 0);
                // RNN 상태 초기화 (모델 의존적)
                newState.put("h", new float[1][128]);
                newState.put("c", new float[1][128]);
                return newState;
            });

            // 오디오 데이터를 float 배열로 변환 (16비트 PCM 가정)
            float[] floatData = convertByteArrayToFloatArray(audioData);

            // 요청 생성 및 전송
            String jsonResponse = sendHttpRequest(sessionId, floatData, sampleRate, sessionState);

            // 응답 처리
            Map<String, Double> result = processResponse(jsonResponse, sessionState);
            
            // 현재 샘플 위치 업데이트
            int currentSample = (int) sessionState.get("currentSample");
            sessionState.put("currentSample", currentSample + floatData.length);

            return result;
        } catch (Exception e) {
            LOG.error("Triton 서버 통신 중 오류 발생: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 오디오 바이트 배열을 float 배열로 변환 (16비트 PCM 가정)
     */
    private float[] convertByteArrayToFloatArray(byte[] audioData) {
        float[] floatData = new float[audioData.length / 2];
        for (int i = 0; i < floatData.length; i++) {
            short sample = (short) ((audioData[i * 2] & 0xFF) | (audioData[i * 2 + 1] << 8));
            floatData[i] = sample / 32767.0f;  // 정규화
        }
        return floatData;
    }

    /**
     * Triton Inference Server에 HTTP 요청 전송
     */
    private String sendHttpRequest(String sessionId, float[] audioData, int sampleRate, 
                                Map<String, Object> state) throws IOException {
        // REST API 엔드포인트 URL
        String url = String.format("http://%s:%d/v2/models/%s/infer", tritonServerUrl, tritonServerPort, modelName);
        if (modelVersion != null && !modelVersion.isEmpty()) {
            url += "/" + modelVersion;
        }
        
        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Content-Type", "application/json");
        
        // 요청 본문 구성
        ObjectNode requestBody = objectMapper.createObjectNode();
        
        // 세션 ID를 요청 메타데이터에 포함
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("session_id", sessionId);
        requestBody.set("parameters", parameters);
        
        // 오디오 데이터 인코딩
        ArrayNode inputsArray = objectMapper.createArrayNode();
        
        // 1. 오디오 입력 데이터
        ArrayNode audioShape = objectMapper.createArrayNode();
        audioShape.add(1);
        audioShape.add(audioData.length);
        
        ObjectNode audioInput = objectMapper.createObjectNode();
        audioInput.put("name", "input");
        audioInput.put("datatype", "FP32");
        audioInput.set("shape", audioShape);
        
        ArrayNode audioDataArray = objectMapper.createArrayNode();
        for (float f : audioData) {
            audioDataArray.add(f);
        }
        audioInput.set("data", audioDataArray);
        inputsArray.add(audioInput);
        
        // 2. 샘플레이트 설정
        ArrayNode srShape = objectMapper.createArrayNode();
        srShape.add(1);
        
        ObjectNode srInput = objectMapper.createObjectNode();
        srInput.put("name", "sr");
        srInput.put("datatype", "INT64");
        srInput.set("shape", srShape);
        
        ArrayNode srDataArray = objectMapper.createArrayNode();
        srDataArray.add(sampleRate);
        srInput.set("data", srDataArray);
        inputsArray.add(srInput);
        
        // 3. RNN 상태 (h)
        float[][] h = (float[][]) state.get("h");
        ArrayNode hShape = objectMapper.createArrayNode();
        hShape.add(h.length);
        hShape.add(h[0].length);
        
        ObjectNode hInput = objectMapper.createObjectNode();
        hInput.put("name", "h");
        hInput.put("datatype", "FP32");
        hInput.set("shape", hShape);
        
        ArrayNode hDataArray = objectMapper.createArrayNode();
        for (float[] row : h) {
            for (float val : row) {
                hDataArray.add(val);
            }
        }
        hInput.set("data", hDataArray);
        inputsArray.add(hInput);
        
        // 4. RNN 상태 (c)
        float[][] c = (float[][]) state.get("c");
        ArrayNode cShape = objectMapper.createArrayNode();
        cShape.add(c.length);
        cShape.add(c[0].length);
        
        ObjectNode cInput = objectMapper.createObjectNode();
        cInput.put("name", "c");
        cInput.put("datatype", "FP32");
        cInput.set("shape", cShape);
        
        ArrayNode cDataArray = objectMapper.createArrayNode();
        for (float[] row : c) {
            for (float val : row) {
                cDataArray.add(val);
            }
        }
        cInput.set("data", cDataArray);
        inputsArray.add(cInput);
        
        // 출력 텐서 설정
        ArrayNode outputsArray = objectMapper.createArrayNode();
        
        ObjectNode outputTensor = objectMapper.createObjectNode();
        outputTensor.put("name", "output");
        outputsArray.add(outputTensor);
        
        ObjectNode hOutputTensor = objectMapper.createObjectNode();
        hOutputTensor.put("name", "h_out");
        outputsArray.add(hOutputTensor);
        
        ObjectNode cOutputTensor = objectMapper.createObjectNode();
        cOutputTensor.put("name", "c_out");
        outputsArray.add(cOutputTensor);
        
        // 요청 본문 완성
        requestBody.set("inputs", inputsArray);
        requestBody.set("outputs", outputsArray);
        
        // HTTP 요청 전송
        httpPost.setEntity(new StringEntity(objectMapper.writeValueAsString(requestBody)));
        
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new IOException("HTTP 요청 실패: " + response.getStatusLine().getStatusCode());
            }
            return EntityUtils.toString(response.getEntity());
        }
    }

    /**
     * Triton Server 응답 처리
     */
    private Map<String, Double> processResponse(String jsonResponse, Map<String, Object> state) throws IOException {
        Map<String, Double> result = new HashMap<>();
        
        ObjectNode responseNode = (ObjectNode) objectMapper.readTree(jsonResponse);
        ArrayNode outputsArray = (ArrayNode) responseNode.get("outputs");
        
        // 출력에서 예측 결과 추출
        float speechProb = 0;
        
        for (int i = 0; i < outputsArray.size(); i++) {
            ObjectNode output = (ObjectNode) outputsArray.get(i);
            String name = output.get("name").asText();
            
            if ("output".equals(name)) {
                // 음성 확률 추출
                ArrayNode data = (ArrayNode) output.get("data");
                speechProb = data.get(0).floatValue();
            } else if ("h_out".equals(name)) {
                // h 상태 업데이트
                float[][] h = (float[][]) state.get("h");
                ArrayNode data = (ArrayNode) output.get("data");
                int index = 0;
                for (int row = 0; row < h.length; row++) {
                    for (int col = 0; col < h[row].length; col++) {
                        h[row][col] = data.get(index++).floatValue();
                    }
                }
                state.put("h", h);
            } else if ("c_out".equals(name)) {
                // c 상태 업데이트
                float[][] c = (float[][]) state.get("c");
                ArrayNode data = (ArrayNode) output.get("data");
                int index = 0;
                for (int row = 0; row < c.length; row++) {
                    for (int col = 0; col < c[row].length; col++) {
                        c[row][col] = data.get(index++).floatValue();
                    }
                }
                state.put("c", c);
            }
        }
        
        // VAD 결정 로직 (SileroVadDetector의 로직과 유사하게 구현)
        boolean triggered = (boolean) state.get("triggered");
        int tempEnd = (int) state.get("tempEnd");
        int currentSample = (int) state.get("currentSample");
        
        // speechProb 처리 로직
        float startThreshold = 0.6f;
        float endThreshold = 0.45f;
        int samplingRate = 16000; // 또는 사용된 샘플링 레이트
        float speechPadSamples = samplingRate * 0.5f; // 500ms
        float minSilenceSamples = samplingRate * 0.6f; // 600ms
        
        if (speechProb >= startThreshold && tempEnd != 0) {
            tempEnd = 0;
            state.put("tempEnd", tempEnd);
        }
        
        if (speechProb >= startThreshold && !triggered) {
            triggered = true;
            state.put("triggered", triggered);
            int speechStart = (int) (currentSample - speechPadSamples);
            speechStart = Math.max(speechStart, 0);
            double speechStartSeconds = speechStart / (double) samplingRate;
            result.put("start", Math.round(speechStartSeconds * 10) / 10.0);
        }
        
        if (speechProb < endThreshold && triggered) {
            if (tempEnd == 0) {
                tempEnd = currentSample;
                state.put("tempEnd", tempEnd);
            }
            
            if (currentSample - tempEnd >= minSilenceSamples) {
                int speechEnd = (int) (tempEnd + speechPadSamples);
                tempEnd = 0;
                triggered = false;
                state.put("tempEnd", tempEnd);
                state.put("triggered", triggered);
                double speechEndSeconds = speechEnd / (double) samplingRate;
                result.put("end", Math.round(speechEndSeconds * 10) / 10.0);
            }
        }
        
        return result;
    }

    /**
     * 세션의 상태를 초기화합니다.
     */
    public void resetSession(String sessionId) {
        sessionStates.remove(sessionId);
    }
} 
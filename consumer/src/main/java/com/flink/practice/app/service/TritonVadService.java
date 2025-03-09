package com.flink.practice.app.service;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.OkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


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

    private OkHttpClient httpClient;
    private ObjectMapper objectMapper;

    // 세션별 상태 관리
    private final ConcurrentMap<String, Map<String, Object>> sessionStates = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        httpClient = new OkHttpClient();
        objectMapper = new ObjectMapper();
    }

    @PreDestroy
    public void cleanup() {
        // OkHttpClient는 close()를 제공하지 않으므로 별도 종료 처리 불필요
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
                // RNN 상태 초기화 (예시로 1x128 배열)
                newState.put("h", new float[1][128]);
                newState.put("c", new float[1][128]);
                return newState;
            });

            // 오디오 데이터를 float 배열로 변환 (16비트 PCM 가정)
            float[] floatData = convertByteArrayToFloatArray(audioData);

            // HTTP 요청 전송
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

    private float[] convertByteArrayToFloatArray(byte[] audioData) {
        float[] floatData = new float[audioData.length / 2];
        for (int i = 0; i < floatData.length; i++) {
            short sample = (short) ((audioData[i * 2] & 0xFF) | (audioData[i * 2 + 1] << 8));
            floatData[i] = sample / 32767.0f;
        }
        return floatData;
    }

    /**
     * OkHttpClient를 사용하여 Triton Server에 HTTP 요청을 전송합니다.
     */
    private String sendHttpRequest(String sessionId, float[] audioData, int sampleRate, Map<String, Object> state) throws IOException {
        // REST API 엔드포인트 URL 구성
        String url = String.format("http://%s:%d/v2/models/%s/infer", tritonServerUrl, tritonServerPort, modelName);
        if (modelVersion != null && !modelVersion.isEmpty()) {
            url += "/" + modelVersion;
        }

        // 요청 본문 구성
        ObjectNode requestBody = objectMapper.createObjectNode();

        // 세션 ID를 파라미터에 포함 (추가 메타데이터)
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("session_id", sessionId);
        requestBody.set("parameters", parameters);

        // 입력 텐서 구성
        ArrayNode inputsArray = objectMapper.createArrayNode();

        // 1. 오디오 데이터 입력 텐서 ("input")
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

        // 2. 샘플레이트 입력 ("sr")
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

        // 3. RNN 상태 입력 ("h")
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

        // 4. RNN 상태 입력 ("c")
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

        requestBody.set("inputs", inputsArray);

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
        requestBody.set("outputs", outputsArray);

        // JSON 문자열로 변환
        String jsonString = objectMapper.writeValueAsString(requestBody);

        // OkHttp RequestBody 생성
        RequestBody body = RequestBody.create(jsonString, MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        // HTTP 요청 전송
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP request failed: " + response.code());
            }
            return response.body().string();
        }
    }

    /**
     * Triton Server 응답 처리
     */
    private Map<String, Double> processResponse(String jsonResponse, Map<String, Object> state) throws IOException {
        Map<String, Double> result = new HashMap<>();
        ObjectNode responseNode = (ObjectNode) objectMapper.readTree(jsonResponse);
        ArrayNode outputsArray = (ArrayNode) responseNode.get("outputs");
        
        float speechProb = 0;
        for (int i = 0; i < outputsArray.size(); i++) {
            ObjectNode output = (ObjectNode) outputsArray.get(i);
            String name = output.get("name").asText();
            if ("output".equals(name)) {
                ArrayNode data = (ArrayNode) output.get("data");
                speechProb = data.get(0).floatValue();
            } else if ("h_out".equals(name)) {
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
        
        // VAD 결정 로직 (예시)
        boolean triggered = (boolean) state.get("triggered");
        int tempEnd = (int) state.get("tempEnd");
        int currentSample = (int) state.get("currentSample");
        float startThreshold = 0.6f;
        float endThreshold = 0.45f;
        int samplingRate = 16000;
        float speechPadSamples = samplingRate * 0.5f;
        float minSilenceSamples = samplingRate * 0.6f;
        
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
     * 세션 상태 초기화
     */
    public void resetSession(String sessionId) {
        // 구현에 따라 세션 상태를 제거
    }
}

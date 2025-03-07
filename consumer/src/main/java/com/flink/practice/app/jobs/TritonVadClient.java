package com.flink.practice.app.jobs;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triton Inference Server와 통신하는 클라이언트 (HTTP REST API 사용)
 * Flink 작업에서 사용하기 위해 직렬화 가능
 */
public class TritonVadClient implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(TritonVadClient.class);
    
    // 직렬화 가능한 설정 필드
    private final String serverUrl;
    private final int serverPort;
    private final String modelName;
    private final String modelVersion;
    
    // 직렬화되지 않는 필드
    private transient CloseableHttpClient httpClient;
    private transient ObjectMapper objectMapper;
    
    // 세션 상태 (직렬화 가능)
    private boolean triggered = false;
    private int tempEnd = 0;
    private int currentSample = 0;
    private final float[][] h = new float[1][128]; // RNN 상태
    private final float[][] c = new float[1][128]; // RNN 상태
    
    // 설정값
    private final float startThreshold = 0.6f;
    private final float endThreshold = 0.45f;
    private final int samplingRate; // 주입받은 샘플링 레이트
    private final float speechPadSamples;
    private final float minSilenceSamples;
    
    /**
     * 생성자
     */
    public TritonVadClient(String serverUrl, int serverPort, String modelName, String modelVersion, int samplingRate) {
        this.serverUrl = serverUrl;
        this.serverPort = serverPort;
        this.modelName = modelName;
        this.modelVersion = modelVersion;
        this.samplingRate = samplingRate;
        this.speechPadSamples = samplingRate * 0.5f; // 500ms
        this.minSilenceSamples = samplingRate * 0.6f; // 600ms
        
        // HTTP 클라이언트 초기화
        initializeHttpClient();
    }
    
    /**
     * HTTP 클라이언트 초기화 (직렬화 후에도 호출 필요)
     */
    public void initializeHttpClient() {
        if (httpClient == null) {
            httpClient = HttpClients.createDefault();
            objectMapper = new ObjectMapper();
        }
    }
    
    /**
     * 연결 종료
     */
    public void close() {
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (IOException e) {
                LOG.error("HTTP 클라이언트 종료 중 오류 발생: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 오디오 청크 처리
     */
    public Map<String, Double> processAudioChunk(byte[] audioData) {
        if (httpClient == null) {
            initializeHttpClient();
        }
        
        try {
            Map<String, Double> result = new HashMap<>();
            
            // 512바이트 청크로 처리 (16비트 PCM 경우 256개 샘플)
            for (int offset = 0; offset < audioData.length; offset += 512) {
                // 마지막 청크가 512바이트보다 작을 경우 처리
                int length = Math.min(512, audioData.length - offset);
                byte[] chunk = new byte[length];
                System.arraycopy(audioData, offset, chunk, 0, length);
                
                // 512바이트가 아닌 경우(마지막 청크) 패딩 처리
                if (length < 512) {
                    byte[] paddedChunk = new byte[512];
                    System.arraycopy(chunk, 0, paddedChunk, 0, length);
                    chunk = paddedChunk;
                }
                
                // HTTP 요청 생성 및 전송
                String jsonResponse = sendHttpRequest(chunk);
                
                // 결과 처리
                Map<String, Double> chunkResult = processResponse(jsonResponse, length / 2);
                
                // 결과 병합
                result.putAll(chunkResult);
            }
            
            return result;
        } catch (Exception e) {
            LOG.error("HTTP 요청 중 오류 발생: {}", e.getMessage());
            Map<String, Double> emptyResult = new HashMap<>();
            return emptyResult;
        }
    }
    
    /**
     * HTTP 요청 생성 및 전송
     */
    private String sendHttpRequest(byte[] audioData) throws IOException {
        // REST API 엔드포인트 URL
        String url = String.format("http://%s:%d/v2/models/%s/infer", serverUrl, serverPort, modelName);
        if (modelVersion != null && !modelVersion.isEmpty()) {
            url += "/" + modelVersion;
        }
        
        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Content-Type", "application/json");
        
        // 요청 본문 구성
        ObjectNode requestBody = objectMapper.createObjectNode();
        ObjectNode inputs = objectMapper.createObjectNode();
        
        // 오디오 데이터 인코딩
        ArrayNode inputShape = objectMapper.createArrayNode();
        inputShape.add(1);
        inputShape.add(audioData.length);
        
        ObjectNode inputData = objectMapper.createObjectNode();
        inputData.put("name", "input");
        inputData.put("datatype", "INT8");
        inputData.set("shape", inputShape);
        
        // 바이트 배열을 Base64로 인코딩
        String base64Audio = Base64.getEncoder().encodeToString(audioData);
        inputData.put("data", base64Audio);
        
        // 샘플레이트 입력
        ArrayNode srShape = objectMapper.createArrayNode();
        srShape.add(1);
        
        ObjectNode srData = objectMapper.createObjectNode();
        srData.put("name", "sr");
        srData.put("datatype", "INT64");
        srData.set("shape", srShape);
        
        ArrayNode srDataArray = objectMapper.createArrayNode();
        srDataArray.add(samplingRate);
        srData.set("data", srDataArray);
        
        // RNN 상태 입력 (h)
        ArrayNode hShape = objectMapper.createArrayNode();
        hShape.add(h.length);
        hShape.add(h[0].length);
        
        ObjectNode hData = objectMapper.createObjectNode();
        hData.put("name", "h");
        hData.put("datatype", "FP32");
        hData.set("shape", hShape);
        
        ArrayNode hDataArray = objectMapper.createArrayNode();
        for (float[] row : h) {
            for (float val : row) {
                hDataArray.add(val);
            }
        }
        hData.set("data", hDataArray);
        
        // RNN 상태 입력 (c)
        ArrayNode cShape = objectMapper.createArrayNode();
        cShape.add(c.length);
        cShape.add(c[0].length);
        
        ObjectNode cData = objectMapper.createObjectNode();
        cData.put("name", "c");
        cData.put("datatype", "FP32");
        cData.set("shape", cShape);
        
        ArrayNode cDataArray = objectMapper.createArrayNode();
        for (float[] row : c) {
            for (float val : row) {
                cDataArray.add(val);
            }
        }
        cData.set("data", cDataArray);
        
        // 입력 배열 생성
        ArrayNode inputsArray = objectMapper.createArrayNode();
        inputsArray.add(inputData);
        inputsArray.add(srData);
        inputsArray.add(hData);
        inputsArray.add(cData);
        
        // 출력 설정
        ArrayNode outputsArray = objectMapper.createArrayNode();
        
        ObjectNode outputData = objectMapper.createObjectNode();
        outputData.put("name", "output");
        outputsArray.add(outputData);
        
        ObjectNode hOutData = objectMapper.createObjectNode();
        hOutData.put("name", "h_out");
        outputsArray.add(hOutData);
        
        ObjectNode cOutData = objectMapper.createObjectNode();
        cOutData.put("name", "c_out");
        outputsArray.add(cOutData);
        
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
     * HTTP 응답 처리
     */
    private Map<String, Double> processResponse(String jsonResponse, int chunkSize) throws IOException {
        Map<String, Double> result = new HashMap<>();
        
        ObjectNode responseNode = (ObjectNode) objectMapper.readTree(jsonResponse);
        ArrayNode outputsArray = (ArrayNode) responseNode.get("outputs");
        
        float speechProb = 0;
        
        // 출력 텐서 처리
        for (int i = 0; i < outputsArray.size(); i++) {
            ObjectNode output = (ObjectNode) outputsArray.get(i);
            String name = output.get("name").asText();
            
            if ("output".equals(name)) {
                // 음성 확률 추출
                ArrayNode data = (ArrayNode) output.get("data");
                speechProb = data.get(0).floatValue();
            } else if ("h_out".equals(name)) {
                // h 상태 업데이트
                ArrayNode data = (ArrayNode) output.get("data");
                int index = 0;
                for (int row = 0; row < h.length; row++) {
                    for (int col = 0; col < h[row].length; col++) {
                        h[row][col] = data.get(index++).floatValue();
                    }
                }
            } else if ("c_out".equals(name)) {
                // c 상태 업데이트
                ArrayNode data = (ArrayNode) output.get("data");
                int index = 0;
                for (int row = 0; row < c.length; row++) {
                    for (int col = 0; col < c[row].length; col++) {
                        c[row][col] = data.get(index++).floatValue();
                    }
                }
            }
        }
        
        // 현재 샘플 위치 업데이트
        currentSample += chunkSize;
        
        // VAD 결정 로직
        if (speechProb >= startThreshold && tempEnd != 0) {
            tempEnd = 0;
        }
        
        if (speechProb >= startThreshold && !triggered) {
            triggered = true;
            int speechStart = (int) (currentSample - speechPadSamples);
            speechStart = Math.max(speechStart, 0);
            double speechStartSeconds = speechStart / (double) samplingRate;
            result.put("start", Math.round(speechStartSeconds * 10) / 10.0);
        }
        
        if (speechProb < endThreshold && triggered) {
            if (tempEnd == 0) {
                tempEnd = currentSample;
            }
            
            if (currentSample - tempEnd >= minSilenceSamples) {
                int speechEnd = (int) (tempEnd + speechPadSamples);
                tempEnd = 0;
                triggered = false;
                double speechEndSeconds = speechEnd / (double) samplingRate;
                result.put("end", Math.round(speechEndSeconds * 10) / 10.0);
            }
        }
        
        return result;
    }
    
    /**
     * 상태 초기화
     */
    public void reset() {
        triggered = false;
        tempEnd = 0;
        currentSample = 0;
        
        // h 및 c 상태 초기화
        for (int i = 0; i < h.length; i++) {
            for (int j = 0; j < h[i].length; j++) {
                h[i][j] = 0.0f;
            }
        }
        
        for (int i = 0; i < c.length; i++) {
            for (int j = 0; j < c[i].length; j++) {
                c[i][j] = 0.0f;
            }
        }
    }
}
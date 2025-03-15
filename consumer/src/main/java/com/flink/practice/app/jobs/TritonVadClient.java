package com.flink.practice.app.jobs;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.Serializable;
import java.util.Base64;

public class TritonVadClient implements Serializable {
    private static final long serialVersionUID = 1L;
    
    // transient 키워드 추가 (직렬화에서 제외)
    private transient OkHttpClient client;
    private final String serverUrl;
    private final String modelName;
    private final String modelVersion;
    private final int samplingRate;
    
    // 시퀀스 ID (생성 시점에 한번 생성)
    private long sequenceId;

    // getter와 setter 추가
    public OkHttpClient getClient() {
        if (client == null) {
            client = new OkHttpClient();
        }
        return client;
    }
    
    public void setClient(OkHttpClient client) {
        this.client = client;
    }
    
    // 나머지 필드에 대한 getter/setter 추가
    public String getServerUrl() {
        return serverUrl;
    }
    
    public String getModelName() {
        return modelName;
    }
    
    public String getModelVersion() {
        return modelVersion;
    }
    
    public int getSamplingRate() {
        return samplingRate;
    }
    
    public long getSequenceId() {
        return sequenceId;
    }
    
    public void setSequenceId(long sequenceId) {
        this.sequenceId = sequenceId;
    }

    public TritonVadClient(String serverUrl, int serverPort, String modelName, String modelVersion, int samplingRate) {
        this.serverUrl = "http://" + serverUrl + ":" + serverPort + "/v2/models/" + modelName + "/infer";
        this.modelName = modelName;
        this.modelVersion = modelVersion;
        this.samplingRate = samplingRate;
        // 현재 타임스탬프를 시퀀스 ID로 사용
        this.sequenceId = System.currentTimeMillis();
    }
    
    public void initializeHttpClient() {
        // 필요시 재초기화 로직 구현
    }
    
    // 시퀀스 ID 재설정
    public void reset() {
        this.sequenceId = System.currentTimeMillis();
    }
    
    /**
     * 오디오 청크를 Triton 서버에 보내고, 시퀀스 제어 정보(parameters)를 포함한 추론 요청을 수행합니다.
     *
     * @param audioChunk 바이트 배열 형태의 오디오 청크
     * @param sequenceStart 스트림 시작 여부
     * @param sequenceEnd 스트림 종료 여부
     * @return 서버 응답 문자열
     * @throws IOException
     */
    public String processAudioChunk(byte[] audioChunk, boolean sequenceStart, boolean sequenceEnd) throws IOException {
        // client가 null인 경우 초기화
        if (client == null) {
            client = new OkHttpClient();
        }
        
        // Base64 인코딩 (AUDIO_CHUNK 입력은 BYTES 타입으로 전달됨)
        String audioChunkB64 = Base64.getEncoder().encodeToString(audioChunk);
        
        // JSON 페이로드 구성 (Triton HTTP API v2 형식)
        JSONObject json = new JSONObject();
        
        // 입력 텐서: AUDIO_CHUNK
        JSONArray inputs = new JSONArray();
        JSONObject input = new JSONObject();
        input.put("name", "AUDIO_CHUNK");
        input.put("shape", new JSONArray(new int[]{1}));
        // 요청에서는 "datatype"을 "BYTES"로 지정합니다.
        input.put("datatype", "BYTES");
        input.put("data", new JSONArray(new String[]{ audioChunkB64 }));
        inputs.put(input);
        json.put("inputs", inputs);
        
        // 출력 텐서: EVENT, TIMESTAMP
        JSONArray outputs = new JSONArray();
        JSONObject outEvent = new JSONObject();
        outEvent.put("name", "EVENT");
        outputs.put(outEvent);
        JSONObject outTimestamp = new JSONObject();
        outTimestamp.put("name", "TIMESTAMP");
        outputs.put(outTimestamp);
        json.put("outputs", outputs);
        
        // 시퀀스 제어 정보: parameters 객체 내에 포함
        JSONObject parameters = new JSONObject();
        parameters.put("sequence_id", sequenceId);
        parameters.put("sequence_start", sequenceStart);
        parameters.put("sequence_end", sequenceEnd);
        json.put("parameters", parameters);
        
        RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(serverUrl)
                .post(body)
                .build();
        
        System.out.println("Sending request to: " + serverUrl);
        System.out.println("Request JSON: " + json.toString(2));
        
        Response response = client.newCall(request).execute();
        if (!response.isSuccessful()) {
            throw new IOException("Unexpected response code: " + response.code());
        }
        String responseBody = response.body().string();
        System.out.println("Received response: " + responseBody);
        return responseBody;
    }
}

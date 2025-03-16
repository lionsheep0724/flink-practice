package com.flink.practice.app.jobs;

import com.flink.practice.app.jobs.TritonVadClient;
import com.flink.practice.app.jobs.AudioChunk;
import com.flink.practice.app.jobs.STTResult;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.lang.StringBuilder;

public class AudioKeyedProcessFunction extends KeyedProcessFunction<String, AudioChunk, STTResult> {

    // 각 세션별 Triton VAD 클라이언트 상태
    private transient ValueState<TritonVadClient> vadClientState;
    
    // 오디오 청크 버퍼링을 위한 상태
    private transient ListState<AudioChunk> audioBufferState;
    
    // 발화 시작 시간 상태
    private transient ValueState<Double> speechStartTimeState;
    
    // 발화 종료 시간 상태
    private transient ValueState<Double> speechEndTimeState;
    
    // STT API URL
    private final String sttApiUrl = "http://stt-fastapi:8080/stt";
    private transient HttpClient httpClient;
    
    // 기타 파라미터 (생성자에서 초기화)
    private final String tritonServerUrl;
    private final int tritonServerPort;
    private final String modelName;
    private final String modelVersion;
    private final int samplingRate;
    private final int bufferOverlapInBytes;

    public AudioKeyedProcessFunction(String tritonServerUrl, int tritonServerPort, 
                                     String modelName, String modelVersion, int samplingRate,
                                     int bufferOverlapInBytes) {
        this.tritonServerUrl = tritonServerUrl;
        this.tritonServerPort = tritonServerPort;
        this.modelName = modelName;
        this.modelVersion = modelVersion;
        this.samplingRate = samplingRate;
        this.bufferOverlapInBytes = bufferOverlapInBytes;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        // VAD 클라이언트 상태 초기화
        ValueStateDescriptor<TritonVadClient> clientDescriptor =
                new ValueStateDescriptor<>("vadClient", TritonVadClient.class);
        vadClientState = getRuntimeContext().getState(clientDescriptor);
        
        // 오디오 버퍼 상태 초기화
        ListStateDescriptor<AudioChunk> bufferDescriptor =
                new ListStateDescriptor<>("audioBuffer", AudioChunk.class);
        audioBufferState = getRuntimeContext().getListState(bufferDescriptor);
        
        // 발화 시작/종료 시간 상태 초기화
        ValueStateDescriptor<Double> startTimeDescriptor =
                new ValueStateDescriptor<>("speechStartTime", Double.class);
        speechStartTimeState = getRuntimeContext().getState(startTimeDescriptor);
        
        ValueStateDescriptor<Double> endTimeDescriptor =
                new ValueStateDescriptor<>("speechEndTime", Double.class);
        speechEndTimeState = getRuntimeContext().getState(endTimeDescriptor);
        
        // HTTP 클라이언트 초기화
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void processElement(AudioChunk value, Context ctx, Collector<STTResult> out) throws Exception {
        // 세션별 VAD 클라이언트 가져오기 (없으면 생성)
        TritonVadClient vadClient = vadClientState.value();
        if (vadClient == null) {
            vadClient = new TritonVadClient(tritonServerUrl, tritonServerPort, modelName, modelVersion, samplingRate);
        }
        
        // 오디오 청크를 버퍼에 추가
        audioBufferState.add(value);
        
        // AudioChunk 클래스에 isFirstChunk(), isLastChunk() 메서드가 있다고 가정합니다.
        boolean isFirst = value.isFirstChunk();
        boolean isLast = value.isLastChunk();
        
        String response;
        if (isFirst) {
            // 첫 청크: 시작 플래그 true, 종료 플래그 false
            System.out.println("Process first chunk");
            response = vadClient.processAudioChunk(value.getData(), true, false);
            System.out.println("Sequence response (start): " + response);
        } else if (isLast) {
            // 마지막 청크: 시작 플래그 false, 종료 플래그 true
            System.out.println("Process last chunk");
            response = vadClient.processAudioChunk(value.getData(), false, true);
            System.out.println("Sequence response (end): " + response);

            // 마지막 청크이고 발화가 시작되었지만 아직 종료되지 않은 경우 처리
            Double speechStart = speechStartTimeState.value();
            if (speechStart != null && speechEndTimeState.value() == null) {
                System.out.println("마지막 청크에서 강제 발화 종료 처리");
                
                try {
                    // 버퍼의 모든 청크를 리스트로 변환
                    List<AudioChunk> audioBuffer = new ArrayList<>();
                    for (AudioChunk chunk : audioBufferState.get()) {
                        audioBuffer.add(chunk);
                    }
                    
                    // 발화 시작 시점부터의 모든 오디오 데이터를 하나의 배열로 결합
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    int startSampleIndex = (int)(speechStart * samplingRate) * 2; // 16비트 = 2바이트/샘플
                    int currentByteIndex = 0;
                    
                    for (AudioChunk chunk : audioBuffer) {
                        byte[] data = chunk.getData();
                        if (currentByteIndex + data.length > startSampleIndex) {
                            int startOffset = Math.max(0, startSampleIndex - currentByteIndex);
                            outputStream.write(data, startOffset, data.length - startOffset);
                        }
                        currentByteIndex += data.length;
                    }
                    
                    byte[] utteranceAudio = outputStream.toByteArray();
                    float[] audioArray = convertByteArrayToFloatArray(utteranceAudio);
                    logAudioArray(audioArray, "강제 종료된 발화");
                    
                    // STT 서버로 오디오 배열 전송
                    String sttResponse = sendAudioToSttServer(audioArray);
                    System.out.println("STT 응답 (강제 종료): " + sttResponse);
                    
                    // JSON 응답에서 텍스트 추출
                    JSONObject sttJson = new JSONObject(sttResponse);
                    String transcription = sttJson.optString("text", "");
                    
                    // STT 결과 생성 및 출력
                    STTResult result = new STTResult();
                    result.setSessionId(ctx.getCurrentKey());
                    result.setText(transcription);
                    result.setStartTime(speechStart);
                    result.setEndTime(-1.0);  // 강제 종료 표시
                    result.setForced(true);
                    
                    out.collect(result);
                } catch (Exception e) {
                    System.err.println("강제 발화 종료 처리 중 오류 발생: " + e.getMessage());
                } finally {
                    // 상태 초기화
                    speechStartTimeState.clear();
                    speechEndTimeState.clear();
                    audioBufferState.clear();
                }
            }
        } else {
            // 중간 청크: 둘 다 false
            System.out.println("Process chunk");
            response = vadClient.processAudioChunk(value.getData(), false, false);
            System.out.println("Sequence response (update): " + response);
        }
        
        // VAD 응답 파싱
        parseVadResponse(response);
        
        // 발화 시작/종료 이벤트가 모두 있는지 확인
        Double speechStart = speechStartTimeState.value();
        Double speechEnd = speechEndTimeState.value();
        
        if (speechStart != null && speechEnd != null) {
            // 완전한 발화가 감지된 경우
            System.out.println("Complete utterance detected: start=" + speechStart + ", end=" + speechEnd);
            
            try {
                // 버퍼에서 발화 부분 추출
                byte[] utteranceAudio = extractUtteranceAudio(speechStart, speechEnd);
                float[] audioArray = convertByteArrayToFloatArray(utteranceAudio);
                logAudioArray(audioArray, "일반 종료된 발화");
                
                // STT 서버로 오디오 배열 전송
                String sttResponse = sendAudioToSttServer(audioArray);
                System.out.println("STT 응답 (일반 종료): " + sttResponse);
                
                // JSON 응답에서 텍스트 추출
                JSONObject sttJson = new JSONObject(sttResponse);
                String transcription = sttJson.optString("text", "");
                
                // STT 결과 생성 및 출력
                STTResult result = new STTResult();
                result.setSessionId(ctx.getCurrentKey());
                result.setText(transcription);
                result.setStartTime(speechStart);
                result.setEndTime(speechEnd);
                result.setForced(false);
                
                out.collect(result);
            } catch (Exception e) {
                System.err.println("Error processing utterance: " + e.getMessage());
            } finally {
                // 상태 초기화
                speechStartTimeState.clear();
                speechEndTimeState.clear();
                audioBufferState.clear();
            }
        }
        
        // 상태 업데이트
        vadClientState.update(vadClient);
    }
    
    // STT 서버로 오디오 배열을 전송하는 메서드
    private String sendAudioToSttServer(float[] audioArray) throws IOException, InterruptedException {
        // 오디오 배열 유효성 검사
        if (audioArray == null || audioArray.length == 0) {
            throw new IllegalArgumentException("오디오 배열이 비어 있거나 null입니다.");
        }
        
        // 오디오 배열 샘플 출력 (디버깅용)
        System.out.println("오디오 배열 샘플 (처음 10개):");
        for (int i = 0; i < Math.min(10, audioArray.length); i++) {
            System.out.printf("%.6f ", audioArray[i]);
        }
        System.out.println();
        
        // JSON 문자열 구성
        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append("{\"audio\":[");
        
        // 배열 값 추가
        for (int i = 0; i < audioArray.length; i++) {
            jsonBuilder.append(audioArray[i]);
            if (i < audioArray.length - 1) {
                jsonBuilder.append(",");
            }
        }
        jsonBuilder.append("]}");
        
        String requestBodyStr = jsonBuilder.toString();
        System.out.println("STT 요청 본문 크기: " + requestBodyStr.length() + " 바이트");
        System.out.println("STT 요청 본문 샘플: " + requestBodyStr.substring(0, Math.min(100, requestBodyStr.length())) + "...");
        
        // HttpURLConnection 사용하여 요청 전송
        System.out.println("HttpURLConnection으로 요청 전송 중...");
        
        // URL 객체 생성
        java.net.URL url = new java.net.URL(sttApiUrl);
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
        
        // 연결 설정
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        
        // 요청 본문 전송
        try (java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(connection.getOutputStream())) {
            writer.write(requestBodyStr);
        }
        
        // 응답 코드 확인
        int responseCode = connection.getResponseCode();
        System.out.println("응답 코드: " + responseCode);
        
        // 응답 본문 읽기
        StringBuilder responseContent = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        responseCode >= 200 && responseCode < 300 
                        ? connection.getInputStream() 
                        : connection.getErrorStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                responseContent.append(line);
            }
        }
        
        String responseBody = responseContent.toString();
        System.out.println("응답 본문: " + responseBody);
        
        // 응답 코드에 따라 처리
        if (responseCode >= 200 && responseCode < 300) {
            return responseBody;
        } else {
            // 오류 발생 시 간단한 테스트 요청 시도
            System.out.println("오류 발생. 간단한 테스트 요청 시도...");
            
            // 테스트용 간단한 요청 본문
            String testBody = "{\"audio\":[0]}";
            
            // 새 연결 생성
            java.net.URL testUrl = new java.net.URL(sttApiUrl);
            java.net.HttpURLConnection testConnection = (java.net.HttpURLConnection) testUrl.openConnection();
            testConnection.setRequestMethod("POST");
            testConnection.setRequestProperty("Content-Type", "application/json");
            testConnection.setDoOutput(true);
            
            // 테스트 요청 본문 전송
            try (java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(testConnection.getOutputStream())) {
                writer.write(testBody);
            }
            
            // 테스트 응답 코드 확인
            int testResponseCode = testConnection.getResponseCode();
            System.out.println("테스트 응답 코드: " + testResponseCode);
            
            // 테스트 응답 본문 읽기
            StringBuilder testResponseContent = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            testResponseCode >= 200 && testResponseCode < 300 
                            ? testConnection.getInputStream() 
                            : testConnection.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    testResponseContent.append(line);
                }
            }
            
            String testResponseBody = testResponseContent.toString();
            System.out.println("테스트 응답 본문: " + testResponseBody);
            
            throw new IOException("STT 서버 오류: " + responseCode + " - " + responseBody);
        }
    }
    
    // VAD 응답 파싱 메서드
    private void parseVadResponse(String response) {
        try {
            JSONObject jsonResponse = new JSONObject(response);
            JSONArray outputs = jsonResponse.getJSONArray("outputs");
            
            String eventType = null;
            Double timestamp = null;
            
            // 각 출력 항목 처리
            for (int i = 0; i < outputs.length(); i++) {
                JSONObject output = outputs.getJSONObject(i);
                String name = output.getString("name");
                
                if ("EVENT".equals(name)) {
                    // EVENT 출력에서 이벤트 유형 추출
                    JSONArray data = output.getJSONArray("data");
                    if (data.length() > 0) {
                        eventType = data.getString(0);
                        System.out.println("Event type: " + eventType);
                    }
                } else if ("TIMESTAMP".equals(name)) {
                    // TIMESTAMP 출력에서 타임스탬프 추출
                    JSONArray data = output.getJSONArray("data");
                    if (data.length() > 0) {
                        timestamp = data.getDouble(0);
                        System.out.println("Timestamp: " + timestamp);
                    }
                }
            }
            
            // 이벤트 유형과 타임스탬프가 모두 있는 경우 처리
            if (eventType != null && timestamp != null) {
                if ("start".equals(eventType)) {
                    // 발화 시작 이벤트
                    speechStartTimeState.update(timestamp);
                    System.out.println("Speech start detected at: " + timestamp);
                } else if ("end".equals(eventType)) {
                    // 발화 종료 이벤트
                    speechEndTimeState.update(timestamp);
                    System.out.println("Speech end detected at: " + timestamp);
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing VAD response: " + e.getMessage());
            e.printStackTrace(); // 스택 트레이스 출력하여 디버깅 용이하게
        }
    }
    
    // 버퍼에서 발화 부분 추출 메서드
    private byte[] extractUtteranceAudio(double startTimeSec, double endTimeSec) throws IOException {
        // 시간을 샘플 인덱스로 변환
        int startSampleIndex = (int)(startTimeSec * samplingRate) * 2; // 16비트 = 2바이트/샘플
        int endSampleIndex = (int)(endTimeSec * samplingRate) * 2;
        
        // 누적 바이트 인덱스 추적
        int currentByteIndex = 0;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        
        // 버퍼의 모든 청크를 리스트로 변환
        List<AudioChunk> audioBuffer = new ArrayList<>();
        try {
            for (AudioChunk chunk : audioBufferState.get()) {
                audioBuffer.add(chunk);
            }
        } catch (Exception e) {
            System.err.println("Error retrieving audio buffer: " + e.getMessage());
            return new byte[0]; // 오류 발생 시 빈 배열 반환
        }
        
        for (AudioChunk chunk : audioBuffer) {
            byte[] data = chunk.getData();
            int chunkEndIndex = currentByteIndex + data.length;
            
            // 이 청크가 발화 범위와 겹치는지 확인
            if (chunkEndIndex > startSampleIndex && currentByteIndex < endSampleIndex) {
                // 발화 부분만 추출
                int startOffset = Math.max(0, startSampleIndex - currentByteIndex);
                int endOffset = Math.min(data.length, endSampleIndex - currentByteIndex);
                
                outputStream.write(data, startOffset, endOffset - startOffset);
            }
            
            currentByteIndex += data.length;
            
            // 발화 종료 이후의 청크는 처리하지 않음
            if (currentByteIndex >= endSampleIndex) break;
        }
        
        return outputStream.toByteArray();
    }

    private float[] convertByteArrayToFloatArray(byte[] audioData) {
        float[] audioFloat = new float[audioData.length / 2];
        for (int i = 0; i < audioFloat.length; i++) {
            short sample = (short) ((audioData[i * 2 + 1] << 8) | (audioData[i * 2] & 0xFF));
            audioFloat[i] = sample / 32768.0f;
        }
        return audioFloat;
    }

    private void logAudioArray(float[] audioArray, String context) {
        System.out.println(context + " - 오디오 배열 정보:");
        System.out.println("배열 길이: " + audioArray.length + " 샘플");
        System.out.println("처음 10개 샘플값:");
        for (int i = 0; i < Math.min(10, audioArray.length); i++) {
            System.out.printf("%.4f ", audioArray[i]);
        }
        System.out.println("\n마지막 10개 샘플값:");
        for (int i = Math.max(0, audioArray.length - 10); i < audioArray.length; i++) {
            System.out.printf("%.4f ", audioArray[i]);
        }
        System.out.println("\n");
    }
}

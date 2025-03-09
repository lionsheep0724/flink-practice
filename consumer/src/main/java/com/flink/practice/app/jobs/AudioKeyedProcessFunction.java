package com.flink.practice.app.jobs;

import com.flink.practice.app.jobs.TritonVadClient;
import com.flink.practice.app.jobs.AudioChunk;
import com.flink.practice.app.jobs.VadResult;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;

public class AudioKeyedProcessFunction extends KeyedProcessFunction<String, AudioChunk, VadResult> {

    // 각 세션별 Triton VAD 클라이언트 상태
    private transient ValueState<TritonVadClient> vadClientState;
    
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
        ValueStateDescriptor<TritonVadClient> clientDescriptor =
                new ValueStateDescriptor<>("vadClient", TritonVadClient.class);
        vadClientState = getRuntimeContext().getState(clientDescriptor);
    }

    @Override
    public void processElement(AudioChunk value, Context ctx, Collector<VadResult> out) throws Exception {
        // 세션별 VAD 클라이언트 가져오기 (없으면 생성)
        TritonVadClient vadClient = vadClientState.value();
        if (vadClient == null) {
            vadClient = new TritonVadClient(tritonServerUrl, tritonServerPort, modelName, modelVersion, samplingRate);
        }
        
        // AudioChunk 클래스에 isFirstChunk(), isLastChunk() 메서드가 있다고 가정합니다.
        boolean isFirst = value.isFirstChunk();
        boolean isLast = value.isLastChunk();
        
        String response;
        if (isFirst) {
            // 첫 청크: 시작 플래그 true, 종료 플래그 false
            response = vadClient.processAudioChunk(value.getData(), true, false);
            System.out.println("Sequence response (start): " + response);
        } else if (isLast) {
            // 마지막 청크: 시작 플래그 false, 종료 플래그 true
            response = vadClient.processAudioChunk(value.getData(), false, true);
            System.out.println("Sequence response (end): " + response);
        } else {
            // 중간 청크: 둘 다 false
            response = vadClient.processAudioChunk(value.getData(), false, false);
            System.out.println("Sequence response (update): " + response);
        }
        
        // 상태 업데이트
        vadClientState.update(vadClient);
        
        // 예시: HTTP 응답 문자열을 이용해 VadResult 생성
        VadResult result = new VadResult(ctx.getCurrentKey(), "HTTP response: " + response);
        out.collect(result);
    }
}

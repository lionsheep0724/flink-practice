package com.flink.practice.app.jobs;

import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class AudioKeyedProcessFunction extends KeyedProcessFunction<String, AudioChunk, VadResult> {

  // 각 세션별 Triton VAD 클라이언트 상태
  private transient ValueState<TritonVadClient> vadClientState;
  
  // 오디오 버퍼링을 위한 상태
  private transient ListState<byte[]> audioBufferState;
  
  // 오버랩 설정
  private final int bufferOverlapInBytes;
  
  // Triton 서버 연결 설정
  private final String tritonServerUrl;
  private final int tritonServerPort;
  private final String modelName;
  private final String modelVersion;
  private final int samplingRate;
  
  // 음성 검출 후 처리 플래그
  private final boolean processAfterVad;

  /**
   * 생성자
   */
  public AudioKeyedProcessFunction(String tritonServerUrl, int tritonServerPort, 
                                  String modelName, String modelVersion, int samplingRate) {
    this(tritonServerUrl, tritonServerPort, modelName, modelVersion, samplingRate, 
         samplingRate);   // 기본 오버랩: 0.5초
  }
  
  /**
   * 오버랩을 지정하는 생성자
   */
  public AudioKeyedProcessFunction(String tritonServerUrl, int tritonServerPort, 
                                  String modelName, String modelVersion, int samplingRate,
                                  int bufferOverlapInBytes) {
    this.tritonServerUrl = tritonServerUrl;
    this.tritonServerPort = tritonServerPort;
    this.modelName = modelName;
    this.modelVersion = modelVersion;
    this.samplingRate = samplingRate;
    this.bufferOverlapInBytes = bufferOverlapInBytes;
    this.processAfterVad = true; // 음성 검출 후 처리 기본값
  }

  @Override
  public void open(Configuration parameters) throws Exception {
    // TritonVadClient 상태 정의
    ValueStateDescriptor<TritonVadClient> clientDescriptor =
        new ValueStateDescriptor<>("vadClient", TritonVadClient.class);
    vadClientState = getRuntimeContext().getState(clientDescriptor);
    
    // 오디오 버퍼 상태 정의
    ListStateDescriptor<byte[]> bufferDescriptor =
        new ListStateDescriptor<>("audioBuffer", byte[].class);
    audioBufferState = getRuntimeContext().getListState(bufferDescriptor);
  }

  @Override
  public void processElement(AudioChunk value, Context ctx, Collector<VadResult> out) throws Exception {
    // 세션별 VAD 클라이언트 가져오기 (없으면 생성)
    TritonVadClient vadClient = vadClientState.value();
    if (vadClient == null) {
      vadClient = new TritonVadClient(
          tritonServerUrl, 
          tritonServerPort, 
          modelName, 
          modelVersion, 
          samplingRate
      );
    } else {
      // 직렬화 후에는 HTTP 클라이언트를 다시 초기화해야 함
      vadClient.initializeHttpClient();
    }

    // 현재 오디오 데이터를 버퍼에 추가
    byte[] packet = value.getData();
    audioBufferState.add(packet);
    
    // 현재까지의 모든 오디오 데이터를 모아서 처리
    List<byte[]> audioChunks = new ArrayList<>();
    for (byte[] chunk : audioBufferState.get()) {
      audioChunks.add(chunk);
    }
    
    // 모든 오디오 데이터를 하나의 바이트 배열로 병합
    byte[] combinedAudio = combineAudioChunks(audioChunks);
    
    // 병합된 오디오 데이터 처리
    Map<String, Double> vadResult = vadClient.processAudioChunk(combinedAudio);
    
    // VAD 결과가 있을 경우에만 출력으로 전달
    if (!vadResult.isEmpty()) {
      VadResult result = new VadResult(ctx.getCurrentKey(), vadResult.toString());
      out.collect(result);
      
      // "end"가 검출되면 상태 초기화 옵션
      if (vadResult.containsKey("end") && processAfterVad) {
        vadClient.reset();
        // 버퍼도 초기화 (processAfterVad가 true일 경우)
        audioBufferState.clear();
      }
    }

    // 상태 업데이트
    vadClientState.update(vadClient);
  }
  
  /**
   * 오디오 청크들을 하나의 바이트 배열로 결합하되, 최근 512바이트만 반환
   */
  private byte[] combineAudioChunks(List<byte[]> chunks) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    
    // 모든 청크를 하나의 스트림으로 결합
    for (byte[] chunk : chunks) {
      outputStream.write(chunk);
    }
    
    byte[] allAudio = outputStream.toByteArray();
    int totalLength = allAudio.length;
    
    // 최근 512바이트만 반환 (Triton VAD가 512바이트 청크로 작동)
    final int TARGET_SIZE = 512;
    
    if (totalLength <= TARGET_SIZE) {
      // 전체 오디오가 512바이트 이하인 경우 그대로 반환
      return allAudio;
    } else {
      // 오디오가 512바이트보다 크면 마지막 512바이트만 추출
      int offset = totalLength - TARGET_SIZE;
      byte[] result = new byte[TARGET_SIZE];
      System.arraycopy(allAudio, offset, result, 0, TARGET_SIZE);
      return result;
    }
  }

  @Override
  public void close() throws Exception {
    // 리소스 해제 로직
    super.close();
  }
}

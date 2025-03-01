package com.flink.practice.app.jobs;

import com.flink.practice.app.vad.SileroVadDetector;
import ai.onnxruntime.OrtException;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.util.Map;

public class AudioKeyedProcessFunction extends KeyedProcessFunction<String, AudioChunk, VadResult> {

  // 각 세션별 VAD 모델 상태를 저장하기 위한 ValueState
  private transient ValueState<SileroVadDetector> vadState;

  @Override
  public void open(Configuration parameters) throws Exception {
    ValueStateDescriptor<SileroVadDetector> descriptor =
        new ValueStateDescriptor<>("vadDetector", SileroVadDetector.class);
    vadState = getRuntimeContext().getState(descriptor);
  }

  @Override
  public void processElement(AudioChunk value, Context ctx, Collector<VadResult> out) throws Exception {
    SileroVadDetector detector = vadState.value();
    if (detector == null) {
      try {
        detector = new SileroVadDetector(
            "src/main/resources/silero_vad.onnx",
            0.6f, 0.45f, 16000, 600, 500
        );
      } catch (OrtException e) {
        throw new RuntimeException("Error initializing VAD detector", e);
      }
    }

    byte[] packet = value.getData();
    Map<String, Double> vadResult = detector.apply(packet, true);

    if (!vadResult.isEmpty()) {
      // 생성한 VadResult 객체에 sessionId와 결과를 저장
      VadResult result = new VadResult(ctx.getCurrentKey(), vadResult.toString());
      out.collect(result);

      // 예시: "end"가 검출되면 detector 상태를 초기화
      if (vadResult.containsKey("end")) {
        detector.reset();
      }
    }

    // 상태 업데이트
    vadState.update(detector);
  }
}

package com.flink.practice.app.jobs;

import com.flink.practice.app.vad.SileroVadDetector;
import ai.onnxruntime.OrtException;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import com.flink.practice.app.jobs.AudioChunk;
import java.util.Map;

public class AudioKeyedProcessFunction extends KeyedProcessFunction<String, AudioChunk, String> {

  // VAD 모델 초기화 파라미터
  private static final String MODEL_PATH = "src/main/resources/silero_vad.onnx";
  private static final int SAMPLE_RATE = 16000;
  private static final float START_THRESHOLD = 0.6f;
  private static final float END_THRESHOLD = 0.45f;
  private static final int MIN_SILENCE_DURATION_MS = 600;
  private static final int SPEECH_PAD_MS = 500;
  // Keyed state to hold the stateful VAD detector for each session (key)
  private transient ValueState<SileroVadDetector> vadState;

  @Override
  public void open(Configuration parameters) throws Exception {
    // ValueStateDescriptor를 생성합니다.
    // 참고: SileroVadDetector는 일반적으로 직렬화가 어려울 수 있으므로,
    // 실제 사용 시에는 상태 복구 전략(custom serializer 또는 lazy reinitialization)을 고려해야 합니다.
    ValueStateDescriptor<SileroVadDetector> descriptor =
        new ValueStateDescriptor<>("vadDetector", SileroVadDetector.class);
    vadState = getRuntimeContext().getState(descriptor);
  }

  @Override
  public void processElement(AudioChunk value, Context ctx, Collector<String> out) throws Exception {
    // 각 세션별로 VAD 인스턴스를 가져옵니다.
    SileroVadDetector detector = vadState.value();
    if (detector == null) {
      try {
        detector = new SileroVadDetector(
            MODEL_PATH,
            START_THRESHOLD,
            END_THRESHOLD,
            SAMPLE_RATE,
            MIN_SILENCE_DURATION_MS,
            SPEECH_PAD_MS);
      } catch (OrtException e) {
        throw new RuntimeException("Error initializing VAD detector", e);
      }
    }

    // 들어오는 오디오 패킷(5120바이트)을 즉시 VAD 처리합니다.
    byte[] packet = value.getData();
    Map<String, Double> vadResult = detector.apply(packet, true);

    // 결과가 있다면(예: "start" 또는 "end" 키가 존재하면) 결과를 출력합니다.
    if (!vadResult.isEmpty()) {
      out.collect("Session " + ctx.getCurrentKey() + " VAD result: " + vadResult.toString());
      // 예시: "end"가 검출되면 detector 상태를 초기화합니다.
      if (vadResult.containsKey("end")) {
        detector.reset();
      }
    }
    // 업데이트된 detector를 상태에 저장합니다.
    vadState.update(detector);
  }
}

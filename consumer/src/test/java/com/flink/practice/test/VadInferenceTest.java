package com.flink.practice.app.vad;

import ai.onnxruntime.OrtException;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

public class VadInferenceTest {

  @Test
  public void testVadInference() throws OrtException {
    // 실제 모델 파일 경로 (파일이 존재해야 합니다)
    String modelPath = "src/resources/silero_vad.onnx";

    // 실제 VAD Detector 생성 (실제 ONNX 모델 로드)
    SileroVadDetector detector = new SileroVadDetector(
        modelPath,
        0.6f,    // START_THRESHOLD
        0.45f,   // END_THRESHOLD
        16000,   // SAMPLE_RATE
        600,     // MIN_SILENCE_DURATION_MS
        500      // SPEECH_PAD_MS
    );

    // 테스트용 오디오 데이터 생성: 예를 들어 1초 분량의 무음 (16,000 샘플, 16-bit PCM → 32,000 바이트)
    int numSamples = 512;
    byte[] audioData = new byte[numSamples * 2]; // 무음 (모두 0)

    // 실제 모델에 적용
    Map<String, Double> vadResult = detector.apply(audioData, true);

    // 결과가 null이 아니어야 함 (무음인 경우 빈 맵일 수 있음)
    assertNotNull(vadResult, "VAD result should not be null");

    // 결과 출력 (디버깅용)
    System.out.println("Actual VAD result: " + vadResult);
  }
}

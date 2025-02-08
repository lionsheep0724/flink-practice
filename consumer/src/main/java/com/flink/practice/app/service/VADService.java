package com.flink.practice.app.service;

import com.flink.practice.app.vad.SileroVadDetector;
import ai.onnxruntime.OrtException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class VADService {

  // VAD 모델 초기화에 필요한 상수 (필요에 따라 application.properties로 외부화 가능)
  private static final String MODEL_PATH = "src/main/resources/silero_vad.onnx";
  private static final float START_THRESHOLD = 0.6f;
  private static final float END_THRESHOLD = 0.45f;
  private static final int SAMPLE_RATE = 16000;
  private static final int MIN_SILENCE_DURATION_MS = 600;
  private static final int SPEECH_PAD_MS = 500;
  // 각 세션별로 VAD 모델 인스턴스를 저장 (동시 처리를 위한 ConcurrentMap)
  private final ConcurrentMap<String, SileroVadDetector> detectors = new ConcurrentHashMap<>();

  /**
   * sessionId에 해당하는 VAD 인스턴스를 가져오거나, 없으면 새로 생성합니다.
   */
  public SileroVadDetector getDetector(String sessionId) {
    return detectors.computeIfAbsent(sessionId, id -> {
      try {
        return new SileroVadDetector(
            MODEL_PATH,
            START_THRESHOLD,
            END_THRESHOLD,
            SAMPLE_RATE,
            MIN_SILENCE_DURATION_MS,
            SPEECH_PAD_MS
        );
      } catch (OrtException e) {
        throw new RuntimeException("Failed to initialize VAD for session: " + id, e);
      }
    });
  }

  /**
   * sessionId에 해당하는 VAD 인스턴스로 오디오 청크를 처리합니다.
   * @param sessionId 고유 세션 식별자
   * @param chunk     오디오 데이터 청크 (byte 배열)
   * @return VAD 처리 결과 (예: "start"나 "end" 인덱스를 포함하는 맵)
   */
  public Map<String, Double> processChunk(String sessionId, byte[] chunk) {
    SileroVadDetector detector = getDetector(sessionId);
    return detector.apply(chunk, true);
  }

  /**
   * 해당 sessionId의 VAD 인스턴스를 초기화합니다.
   */
  public void resetDetector(String sessionId) {
    SileroVadDetector detector = detectors.get(sessionId);
    if (detector != null) {
      detector.reset();
    }
  }

  /**
   * 세션 종료 시 해당 VAD 인스턴스를 제거합니다.
   */
  public void removeDetector(String sessionId) {
    detectors.remove(sessionId);
  }
}

package com.flink.practice.app.service;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.flink.practice.app.vad.SileroVadDetector;
import ai.onnxruntime.OrtException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class FlinkProcessingService {

  // 예시 파라미터 (실제 값은 환경에 맞게 설정)
  private static final String MODEL_PATH = "src/main/resources/silero_vad.onnx";
  private static final float START_THRESHOLD = 0.6f;
  private static final float END_THRESHOLD = 0.45f;
  private static final int SAMPLE_RATE = 16000;
  private static final int MIN_SILENCE_DURATION_MS = 600;
  private static final int SPEECH_PAD_MS = 500;
  // 각 세션별로 VAD 모델 인스턴스를 저장 (동시에 여러 세션을 처리할 수 있도록)
  private final ConcurrentMap<String, SileroVadDetector> vadDetectors = new ConcurrentHashMap<>();

  /**
   * 각 세션별 오디오 스트림을 인메모리에서 누적하고, VAD 모델로 처리한 후 결과에 따라 슬라이싱하여 후속 처리하는 메서드.
   *
   * @param sessionId 고유한 세션 식별자
   * @param audioFlux 오디오 청크들을 담은 Flux<byte[]>
   */
  public void processAndPublish(String sessionId, Flux<byte[]> audioFlux) {
    // 세션마다 새로운 VAD 인스턴스를 생성하여 상태를 유지
    SileroVadDetector detector = vadDetectors.computeIfAbsent(sessionId, id -> {
      try {
        return new SileroVadDetector(
            MODEL_PATH,
            START_THRESHOLD,
            END_THRESHOLD,
            SAMPLE_RATE,
            MIN_SILENCE_DURATION_MS,
            SPEECH_PAD_MS);
      } catch (OrtException e) {
        throw new RuntimeException("Error initializing VAD detector for session " + id, e);
      }
    });

    // 누적 버퍼 (Python의 bytearray와 유사)
    ByteArrayOutputStream accumulator = new ByteArrayOutputStream();

    audioFlux.subscribe(chunk -> {
      try {
        // 매 청크가 들어올 때마다 누적 버퍼에 추가
        accumulator.write(chunk);
        System.out.println("Session " + sessionId + ": Accumulated " + chunk.length
            + " bytes. Total buffered: " + accumulator.size() + " bytes.");

        // VAD 모델에 새 청크를 적용해서 내부 상태를 업데이트하고, 발화 경계를 감지
        // (여기서는 단순히 detector.apply()를 호출하여 결과 맵을 받는 예시)
        Map<String, Double> vadResult = detector.apply(chunk, true);
        // 결과 맵에 "start"나 "end" 키가 있으면 발화 경계를 검출한 것으로 간주
        if (vadResult.containsKey("start") || vadResult.containsKey("end")) {
          // 예시로, 전체 누적된 데이터를 슬라이싱하는 로직 (실제 구현에서는 detector의 상태를 참조)
          byte[] bufferedData = accumulator.toByteArray();
          // detector가 반환한 인덱스(초 단위)를 샘플 단위로 변환 (예시)
          int startSample = vadResult.containsKey("start")
              ? (int)(vadResult.get("start") * SAMPLE_RATE) : 0;
          int endSample = vadResult.containsKey("end")
              ? (int)(vadResult.get("end") * SAMPLE_RATE) : bufferedData.length;
          // 슬라이싱 수행 (간단한 예시)
          int sliceLength = Math.min(endSample - startSample, bufferedData.length - startSample);
          byte[] slicedBuffer = new byte[sliceLength];
          System.arraycopy(bufferedData, startSample, slicedBuffer, 0, sliceLength);

          System.out.println("Session " + sessionId + ": Detected boundaries -> start: "
              + startSample + ", end: " + endSample
              + ". Sliced buffer size: " + slicedBuffer.length + " bytes.");

          // TODO: 이 slicedBuffer를 STT 서버에 전송하거나 Kafka에 게시하는 로직 구현

          // 처리 후, 누적 버퍼와 VAD 상태를 초기화
          accumulator.reset();
          detector.reset();
        }

      } catch (IOException e) {
        throw new RuntimeException("Error accumulating audio data for session " + sessionId, e);
      }
    }, error -> {
      System.err.println("Error in session " + sessionId + ": " + error.getMessage());
    }, () -> {
      // 스트림 종료 시 처리 (필요하면)
      System.out.println("Session " + sessionId + " completed. Final buffered size: "
          + accumulator.size() + " bytes.");
      // 세션 종료 시 VAD 인스턴스 제거 (옵션)
      vadDetectors.remove(sessionId);
    });
  }
}

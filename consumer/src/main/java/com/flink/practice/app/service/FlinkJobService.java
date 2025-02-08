package com.flink.practice.app.service;

import com.flink.practice.app.jobs.AudioStreamProcessingJob;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class FlinkJobService {

  @Async  // 별도의 스레드에서 실행하여 메인 스레드를 블록하지 않도록 함
  public void submitJob() {
    try {
      // 로컬 모드에서 Flink 스트리밍 실행 환경 생성
      final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
      // 필요에 따라 체크포인트 등 추가 설정
      env.enableCheckpointing(10000);

      // AudioStreamProcessingJob은 Flink Job의 파이프라인을 구성하는 클래스입니다.
      // 실제 소스는 Kafka 등 외부 시스템에서 공급할 수 있으나, 여기서는 예시로 간단하게 fromElements()를 사용합니다.
      env.fromElements(
              "dummy element 1",
              "dummy element 2"
          )
          .print();

      // 스트리밍 작업은 보통 무한 실행되므로, 실행 환경의 execute() 메서드는 별도 스레드에서 블록됩니다.
      env.execute("Embedded Flink Job");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}

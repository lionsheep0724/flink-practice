package com.flink.practice.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication(scanBasePackages = "com.flink.practice.app")
@EnableKafka  // Kafka 리스너 활성화를 위해 추가
public class AudioStreamingApplication {

  public static void main(String[] args) {
    SpringApplication.run(AudioStreamingApplication.class, args);
  }
}

package com.flink.practice.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = "com.flink.practice.app")
@EnableAsync
public class FlinkApplication {
  public static void main(String[] args) {
    SpringApplication.run(FlinkApplication.class, args);
  }
}

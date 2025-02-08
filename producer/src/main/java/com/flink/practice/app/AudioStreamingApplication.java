package com.flink.practice.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.flink.practice.app")
public class AudioStreamingApplication {

  public static void main(String[] args) {
    SpringApplication.run(AudioStreamingApplication.class, args);
  }
}

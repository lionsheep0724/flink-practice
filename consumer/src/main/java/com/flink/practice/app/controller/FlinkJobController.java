package com.flink.practice.app.controller;

import com.flink.practice.app.jobs.AudioStreamProcessingJob;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FlinkJobController {

  private final AudioStreamProcessingJob audioStreamProcessingJob;

  public FlinkJobController(AudioStreamProcessingJob audioStreamProcessingJob) {
    this.audioStreamProcessingJob = audioStreamProcessingJob;
  }

  @GetMapping("/submitFlinkJob")
  public String submitJob() {
    audioStreamProcessingJob.submitJob();
    return "Flink Job submitted!";
  }
}

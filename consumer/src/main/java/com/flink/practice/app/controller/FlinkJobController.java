package com.flink.practice.app.controller;

import com.flink.practice.app.service.FlinkJobService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FlinkJobController {

  private final FlinkJobService flinkJobService;

  public FlinkJobController(FlinkJobService flinkJobService) {
    this.flinkJobService = flinkJobService;
  }

  @GetMapping("/submitFlinkJob")
  public String submitJob() {
    flinkJobService.submitJob();
    return "Flink Job submitted!";
  }
}

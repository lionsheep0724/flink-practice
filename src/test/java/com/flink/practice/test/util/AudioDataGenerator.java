package com.flink.practice.test.util;

import java.util.Random;

public class AudioDataGenerator {
  private static final Random RANDOM = new Random();
  private static final int CHUNK_SIZE = 5120; // 5120바이트 (약 5KB)

  public static byte[] generateRandomAudioChunk() {
    byte[] data = new byte[CHUNK_SIZE];
    RANDOM.nextBytes(data); // 랜덤한 바이트들로 채웁니다.
    return data;
  }
}

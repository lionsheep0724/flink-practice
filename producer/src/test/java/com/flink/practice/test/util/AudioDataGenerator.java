package com.flink.practice.test.util;

import java.util.Random;

public class AudioDataGenerator {

    private static final Random random = new Random();

    /**
     * 무작위로 size 바이트 길이의 오디오 청크 데이터를 생성.
     */
    public static byte[] generateRandomAudioChunk(int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);
        return data;
    }

    /**
     * 기본 (예: 1024 bytes) 무작위 오디오 청크 생성
     */
    public static byte[] generateRandomAudioChunk() {
        return generateRandomAudioChunk(1024);
    }
}

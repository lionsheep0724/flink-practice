package com.flink.practice.test;

import com.flink.practice.app.jobs.TritonVadClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

public class TritonVadClientIntegrationTest {

    // 테스트 매개변수 (실제 서버 환경에 맞게 수정)
    private final String SERVER_URL = "localhost";
    private final int SERVER_PORT = 8000;
    private final String MODEL = "vad_model";
    private final String VERSION = "1";
    private final int SAMPLING_RATE = 16000;

    @Test
    public void testProcessAudioChunk_SplitInto1280ByteChunks() {
        // 총 5120바이트의 테스트 데이터 생성 (5120바이트 = 2560 샘플, 16비트 PCM)
        int totalBytes = 5120;
        int numChunks = 4;
        int chunkSize = totalBytes / numChunks; // 1280 bytes per chunk
        byte[] testAudio = new byte[totalBytes];
        new Random().nextBytes(testAudio);

        // TritonVadClient 인스턴스 생성 (실제 서버에 요청)
        TritonVadClient client = new TritonVadClient(SERVER_URL, SERVER_PORT, MODEL, VERSION, SAMPLING_RATE);

        for (int i = 0; i < numChunks; i++) {
            byte[] chunk = Arrays.copyOfRange(testAudio, i * chunkSize, (i + 1) * chunkSize);
            // 첫 청크: sequence_start=true, sequence_end=false
            // 중간 청크들: 둘 다 false
            // 마지막 청크: sequence_start=false, sequence_end=true
            boolean sequenceStart = (i == 0);
            boolean sequenceEnd = (i == numChunks - 1);
            try {
                System.out.println("Sending request for chunk " + (i + 1));
                String responseJson = client.processAudioChunk(chunk, sequenceStart, sequenceEnd);
                System.out.println("Response JSON for chunk " + (i + 1) + ":");
                System.out.println(responseJson);
                assertNotNull(responseJson, "응답 JSON은 null이 아니어야 합니다");
            } catch (IOException e) {
                fail("청크 " + (i + 1) + " 전송 중 예외 발생: " + e.getMessage());
            }
        }
    }
}

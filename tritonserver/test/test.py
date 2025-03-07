import base64
import numpy as np
import tritonclient.http as httpclient

# Triton Server 설정
url = "localhost:8000"
model_name = "vad_model"
model_version = "1"

# Triton 클라이언트 생성
client = httpclient.InferenceServerClient(url=url)

# 테스트용 랜덤 오디오 데이터 생성: 5120 바이트
audio_data = np.random.randint(0, 256, size=5120, dtype=np.uint8).tobytes()
# base64 인코딩 (모델의 AUDIO_CHUNK 입력은 문자열로 전달됨)
audio_chunk_b64 = base64.b64encode(audio_data).decode('utf-8')
# 입력은 1차원 배열(길이 1)로 전달
audio_chunk_np = np.array([audio_chunk_b64], dtype=object)

# InferInput 객체 생성 및 데이터 설정
inputs = []
inputs.append(httpclient.InferInput("AUDIO_CHUNK", audio_chunk_np.shape, "BYTES"))
inputs[0].set_data_from_numpy(audio_chunk_np)

# InferRequestedOutput 객체 생성 (EVENT, TIMESTAMP)
outputs = []
outputs.append(httpclient.InferRequestedOutput("EVENT"))
outputs.append(httpclient.InferRequestedOutput("TIMESTAMP"))

# Sequence 정보를 포함하여 추론 요청 (예시로 임의의 sequence_id 사용)
sequence_id = 123456789
# 첫 번째 요청: 시퀀스 시작을 알림
result = client.infer(
    model_name=model_name,
    model_version=model_version,
    inputs=inputs,
    outputs=outputs,
    sequence_id=sequence_id,
    sequence_start=True,
    sequence_end=True  # 단일 요청으로 시작과 종료를 함께 보냄
)

# 출력 결과 추출 및 출력
event = result.as_numpy("EVENT")
timestamp = result.as_numpy("TIMESTAMP")
print("EVENT:", event)
print("TIMESTAMP:", timestamp)

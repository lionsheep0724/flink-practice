# filename: main.py
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import numpy as np
from transformers import pipeline

app = FastAPI(title="STT ENGINE API")

# 입력 데이터 모델 정의: 오디오 데이터를 float 배열로 받습니다.
class AudioData(BaseModel):
    audio: list[float]
    # 필요하다면 sample_rate를 옵션으로 추가할 수 있습니다.
    # sample_rate: int = 16000

# Whisper v2 large 모델을 로드하는 ASR 파이프라인 생성 (CPU 또는 GPU 사용)
model = pipeline("automatic-speech-recognition", model="openai/whisper-large-v2")

@app.post("/stt")
async def transcribe_audio(audio_data: AudioData):
    try:
        # 입력으로 받은 리스트를 numpy 배열로 변환 (dtype: float32)
        audio_array = np.array(audio_data.audio, dtype=np.float32)
        # 입력 데이터가 1차원 배열이 아닐 경우, flatten 처리
        if audio_array.ndim != 1:
            audio_array = audio_array.flatten()

        # Whisper 모델은 보통 16kHz 샘플레이트를 사용합니다.
        # (만약 sample_rate를 입력으로 받고 싶다면 AudioData 모델에 추가하세요.)
        sampling_rate = 16000

        # ASR 추론 수행: 모델이 numpy 배열을 직접 지원하도록 pipeline이 처리합니다.
        result = model(audio_array)
        print(f"STT RESULT : {result}")
        return result["text"]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)

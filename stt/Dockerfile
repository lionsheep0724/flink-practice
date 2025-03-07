# Python 3.9 slim 베이스 이미지 사용 (필요 시 다른 버전 선택 가능)
FROM python:3.9-slim


# 작업 디렉토리 설정
WORKDIR /app

# requirements.txt를 컨테이너에 복사하고 의존성 설치
COPY requirements.txt .
RUN pip install --upgrade pip && pip install -r requirements.txt

# 전체 애플리케이션 코드 복사 (main.py 등 포함)
COPY . .

# FastAPI 서비스가 사용할 포트 노출 (기본적으로 uvicorn은 8000 포트 사용)
EXPOSE 8001

# 컨테이너 시작 시 uvicorn을 통해 FastAPI 애플리케이션 실행
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]

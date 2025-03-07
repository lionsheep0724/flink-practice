FROM nvcr.io/nvidia/tritonserver:25.02-vllm-python-py3

WORKDIR /models

COPY model_repository/ /models/

EXPOSE 8000 8001 8002

RUN pip install torchaudio==2.6.0
RUN pip install onnxruntime==1.20.1
CMD ["tritonserver", "--model-repository=/models"]

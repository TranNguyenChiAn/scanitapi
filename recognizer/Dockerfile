FROM python:3.11-slim
ENV PYTHONUNBUFFERED=1 HF_HOME=/models PIP_NO_CACHE_DIR=1
WORKDIR /app
# Ban CPU cua torch nho hon nhieu so voi ban CUDA mac dinh
RUN pip install torch==2.5.1 torchvision==0.20.1 --index-url https://download.pytorch.org/whl/cpu
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY embedder.py app.py ./
EXPOSE 8001
# 1 worker: model nam trong RAM (~1GB); nhieu worker se nhan doi bo nho
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8001", "--workers", "1"]

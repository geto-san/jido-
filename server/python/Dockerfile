# Legacy yt-dlp resolver, deployed on Render as the Android client's fallback.
#
# Superseded by the Java service in ../src, but deliberately kept working: the
# app still points at this for its last-resort resolve until the Java service
# is deployed and proven. Do not add features here.
FROM python:3.10-slim

# Install system dependencies for yt-dlp
RUN apt-get update && apt-get install -y \
    ffmpeg \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
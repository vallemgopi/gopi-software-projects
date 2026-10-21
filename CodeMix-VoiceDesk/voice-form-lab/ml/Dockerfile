FROM python:3.12-slim
WORKDIR /app
COPY requirements.lock .
RUN pip install --no-cache-dir -r requirements.lock
COPY requirements-speech.lock .
RUN pip install --no-cache-dir -r requirements-speech.lock
COPY . .
RUN useradd -m lab
USER lab
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]

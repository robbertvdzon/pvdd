FROM python:3.13-alpine
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY server.py /app/server.py
COPY fixtures /app/fixtures
USER 1001
EXPOSE 8080
CMD ["python", "/app/server.py"]

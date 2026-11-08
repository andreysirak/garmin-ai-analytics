FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY garmin-integration/garmin-auth/python/pyproject.toml ./pyproject.toml
COPY garmin-integration/garmin-auth/python/src ./src
RUN pip install --no-cache-dir .

USER 10001:10001
ENTRYPOINT ["garmin-auth"]

FROM apache/airflow:3.3.1-python3.11

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl maven \
    && arch="$(dpkg --print-architecture)" \
    && case "$arch" in \
        amd64) temurin_arch='x64' ;; \
        arm64) temurin_arch='aarch64' ;; \
        *) echo "Unsupported architecture: $arch" && exit 1 ;; \
      esac \
    && curl -fsSL -o /tmp/jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/25/ga/linux/${temurin_arch}/jdk/hotspot/normal/eclipse" \
    && mkdir -p /opt/java \
    && tar -xzf /tmp/jdk.tar.gz -C /opt/java --strip-components=1 \
    && rm /tmp/jdk.tar.gz \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/opt/java
ENV PATH="/opt/java/bin:${PATH}"
USER airflow

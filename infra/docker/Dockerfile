# ==============================================================================
# Multi-Stage Production Dockerfile for Ultra-Low Latency Java HFT Platform
# Base: Ubuntu 22.04 LTS with OpenJDK 21 & Real-Time Kernel Bypass Tooling
# Privileges Required: CAP_SYS_NICE, CAP_NET_RAW, CAP_IPC_LOCK, Host Networking
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Build & Packaging
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

# Install Maven & compilation dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    maven \
    git \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Copy POM and download dependencies for cache layer
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Copy source code and build project
COPY src ./src
RUN mvn clean package -DskipTests

# ------------------------------------------------------------------------------
# Stage 2: Ultra-Low Latency Production Runtime Image
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

LABEL maintainer="HFT Infrastructure Team <infra@hft-platform.internal>"
LABEL description="Ultra-Low Latency Java 21 Trading Engine Container"

# Install hardware, NUMA, networking, and PTP inspection utilities
RUN apt-get update && apt-get install -y --no-install-recommends \
    numactl \
    ethtool \
    linuxptp \
    iproute2 \
    procps \
    libcap2-bin \
    kmod \
    pciutils \
    && rm -rf /var/lib/apt/lists/*

# Create dedicated application user and log directories
RUN groupadd -g 2000 hft && \
    useradd -u 2000 -g hft -m -s /bin/bash hft && \
    mkdir -p /var/log/hft /dev/hugepages1G /dev/hugepages2M /app && \
    chown -R hft:hft /var/log/hft /app

WORKDIR /app

# Copy application artifacts from build stage
COPY --from=builder /build/target/*.jar /app/hft-platform.jar
COPY --from=builder /build/target/classes /app/classes
COPY --from=builder /build/target/test-classes /app/test-classes

# Copy infrastructure configurations and scripts
COPY infra /app/infra
RUN chmod +x /app/infra/jvm/*.sh /app/infra/network/*.sh /app/infra/os-tuning/*.sh /app/infra/docker/entrypoint.sh /app/infra/deploy/*.sh

# Grant realtime privileges (CAP_SYS_NICE, CAP_NET_RAW, CAP_IPC_LOCK) to Java binary
RUN setcap 'cap_sys_nice,cap_net_raw,cap_net_admin,cap_ipc_lock=+ep' $(readlink -f $(which java)) || true

EXPOSE 10000-10020/udp
EXPOSE 20000-20010/tcp

ENTRYPOINT ["/app/infra/docker/entrypoint.sh"]
CMD ["production"]

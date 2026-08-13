FROM --platform=linux/arm64 debian:bookworm-slim

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        e2fsprogs \
        git \
        mmdebstrap \
        npm \
        qemu-utils \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
ENTRYPOINT ["/workspace/guest/build-image.sh"]

FROM --platform=linux/arm64 debian:bookworm-slim

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        e2fsprogs \
        mmdebstrap \
        qemu-utils \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
ENTRYPOINT ["/workspace/guest/build-image.sh"]

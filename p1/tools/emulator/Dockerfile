# Android emulator in a container: isolates it from host-wide /etc/ld.so.preload hooks
# (e.g. Citrix libAppProtection.so), which crash qemu's allocator on this host.
FROM ubuntu:24.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      libc6 libstdc++6 libgl1 libegl1 libx11-6 libx11-xcb1 libxcb1 libxext6 libxrender1 libxi6 libxfixes3 \
      libxcursor1 libxrandr2 libxkbcommon0 libxkbcommon-x11-0 libnss3 libdbus-1-3 libpulse0 libasound2t64 \
      libfontconfig1 libfreetype6 libglib2.0-0t64 libdrm2 libgbm1 libxcomposite1 libxdamage1 libxtst6 \
      libxshmfence1 libnspr4 libexpat1 libz1 libbsd0 file procps \
 && rm -rf /var/lib/apt/lists/*

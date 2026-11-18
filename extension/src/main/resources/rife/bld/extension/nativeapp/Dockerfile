# Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
# Licensed under the Apache License, Version 2.0 (the "License")

# A Linux to build a site as a native library for Android in. native-image compiles the Java
# classes of the JDK that it runs on, and Android has those of Linux, so it can't run on a Mac
# for this. The GraalVM is the one that builds for the Mac, as sdkman has it for Linux on the
# processor of an Apple silicon Mac: GraalVm.java says which, with its checksum.
FROM --platform=linux/arm64 debian:13-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl && rm -rf /var/lib/apt/lists/*
ARG GRAALVM
ARG GRAALVM_SHA256
RUN curl -fsSL -o /tmp/graalvm.tar.gz "https://api.sdkman.io/2/broker/download/java/${GRAALVM}/linuxarm64" \
    && echo "${GRAALVM_SHA256}  /tmp/graalvm.tar.gz" | sha256sum -c - \
    && mkdir -p /opt/graalvm && tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1 && rm /tmp/graalvm.tar.gz
ENV JAVA_HOME=/opt/graalvm PATH=/opt/graalvm/bin:$PATH

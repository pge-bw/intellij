FROM ubuntu:20.04
SHELL ["/bin/bash", "-c"]
RUN apt-get update && \
    DEBIAN_FRONTEND="noninteractive" apt-get install --yes \
    awscli \
    apt-transport-https  \
    bash \
    build-essential \
    curl \
    gcc \
    git \
    gnupg \
    openjdk-8-jdk \
    openjdk-11-jdk \
    python3 \
    zip && \
    # Set default Python version
    ln -s /usr/bin/python3.8 /usr/bin/python

# Bazel installation
RUN curl -fsSL https://bazel.build/bazel-release.pub.gpg | gpg --dearmor > /etc/apt/trusted.gpg.d/bazel.gpg && \
    echo "deb [arch=amd64] https://storage.googleapis.com/bazel-apt stable jdk1.8" | tee /etc/apt/sources.list.d/bazel.list && \
    apt-get update && apt-get install --yes bazel

WORKDIR /root/intellij-bazel
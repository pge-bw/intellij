#!/usr/bin/env bash
set -euo pipefail

GIT_REPO_ROOT=$(git rev-parse --show-toplevel)
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

docker build $SCRIPT_DIR --tag build-bazel-plugin
docker run -it \
   -v ${GIT_REPO_ROOT}:/root/intellij-bazel build-bazel-plugin /bin/bash -c \
   "bazel build //ijwb:ijwb_bazel_zip --define=ij_product=intellij-latest && \
   cp ./bazel-bin/ijwb/ijwb_bazel.zip ./custom_build/"

sha256sum custom_build/ijwb_bazel.zip
aws s3 cp custom_build/ijwb_bazel.zip s3://bwce022-public-bucket/ijwb_bazel_2021.08.09.zip
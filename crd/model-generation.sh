#!/usr/bin/env bash

# Local generation
LOCAL_MANIFEST_FILE=$(readlink -f $(dirname $0))/my-crd.yaml
echo "Generating model classes for $LOCAL_MANIFEST_FILE"
mkdir -p /tmp/java && cd /tmp/java
docker run \
  --rm \
  -v "$LOCAL_MANIFEST_FILE":"$LOCAL_MANIFEST_FILE" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$(pwd)":"$(pwd)" \
  -ti \
  --network host \
  ghcr.io/kubernetes-client/java/crd-model-gen:v1.0.6 \
  /generate.sh \
  -u $LOCAL_MANIFEST_FILE \
  -n prabhu.amrut.com \
  -p com.amrut.prabhu \
  -o "$(pwd)"

#!/usr/bin/env bash
# 下载并放置内嵌的 gh CLI（Linux arm64 静态二进制）
# 用法：bash scripts/fetch-gh.sh
set -euo pipefail
VER="${GH_VERSION:-2.105.0}"
DEST="app/src/main/jniLibs/arm64-v8a/libgh.so"
mkdir -p "$(dirname "$DEST")"
URL="https://github.com/cli/cli/releases/download/v${VER}/gh_${VER}_linux_arm64.tar.gz"
echo "下载 gh ${VER} …"
curl -L --fail -o /tmp/gh.tgz "$URL"
tar -xzf /tmp/gh.tgz -C /tmp
cp "/tmp/gh_${VER}_linux_arm64/bin/gh" "$DEST"
chmod 755 "$DEST"
echo "已放置：$DEST"

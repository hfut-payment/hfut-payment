#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
name=xms-wearable-lib_1.4_release.aar
expected=9c40fd1c5409bb948523d503af71e2978ae522c35636afe7d64f474c0f6bc195
if [[ -f "vendor/$name" ]]; then
  printf '%s  %s\n' "$expected" "vendor/$name" | sha256sum -c -
  exit
fi
archive=$(mktemp)
aar=$(mktemp)
trap 'rm -f -- "$archive" "$aar"' EXIT
curl --fail --silent --show-error --location --proto '=https' --max-time 90 \
  https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip -o "$archive"
unzip -p "$archive" "interconnect_dev_test_demo/libs/$name" > "$aar"
printf '%s  %s\n' "$expected" "$aar" | sha256sum -c -
mkdir -p vendor
mv -- "$aar" "vendor/$name"

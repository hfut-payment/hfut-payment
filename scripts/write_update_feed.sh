#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Pass the path to the verified release APK}
filename=${apk##*/}
root=$(cd "$(dirname "$0")/.." && pwd)
version=$(sed -n 's/^versionName=//p' "$root/version.properties" | tr -d '\r')
code=$(sed -n 's/^versionCode=//p' "$root/version.properties" | tr -d '\r')
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9.-]+)?$ ||
      ! "$code" =~ ^[1-9][0-9]{0,9}$ || "$code" -gt 2100000000 ||
      "$filename" != "HFUT-Campus-Companion-$version.apk" ]]; then
  echo 'The APK filename and Android version must agree with version.properties.' >&2
  exit 1
fi
read -r digest _ < <(sha256sum -- "$apk")
bytes=$(wc -c < "$apk")
printf '{"schema":1,"versionCode":%d,"versionName":"%s","apk":"%s","bytes":%d,"sha256":"%s"}\n' \
  "$code" "$version" "$filename" "$bytes" "$digest" > "$(dirname "$apk")/update.json"

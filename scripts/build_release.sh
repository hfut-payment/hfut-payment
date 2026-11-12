#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(sed -n 's/^versionName=//p' version.properties)
code=$(sed -n 's/^versionCode=//p' version.properties)
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9.-]+)?$ ||
      ! "$code" =~ ^[1-9][0-9]{0,9}$ || "$code" -gt 2100000000 ||
      "${1:-}" != "v$version" ]]; then
  echo 'Release tag and version.properties must agree, with a valid Android versionCode.' >&2
  exit 1
fi

: "${HFUT_SIGNING_P12_B64:?Set the HFUT_SIGNING_P12_B64 repository secret}"
: "${CAMPUS_SIGNING_PASSWORD:?Set the HFUT_SIGNING_PASSWORD repository secret}"

umask 077
sign_dir=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/hfut-sign.XXXXXXXX")
trap 'rm -f -- "$sign_dir/companion.p12" "$sign_dir/certificate.pem"; rmdir -- "$sign_dir"' EXIT
sign_path=$sign_dir
if command -v cygpath >/dev/null 2>&1; then sign_path=$(cygpath -am "$sign_dir"); fi
export CAMPUS_SIGNING_STORE="$sign_path/companion.p12"
printf '%s' "$HFUT_SIGNING_P12_B64" | base64 --decode > "$sign_dir/companion.p12"
unset HFUT_SIGNING_P12_B64
openssl pkcs12 -in "$CAMPUS_SIGNING_STORE" -clcerts -nokeys -passin env:CAMPUS_SIGNING_PASSWORD \
  | openssl x509 -out "$sign_dir/certificate.pem"
expected=$(tr -d '\r\n' < scripts/release-certificate.sha256)
fingerprint=$(openssl x509 -in "$sign_dir/certificate.pem" -noout -fingerprint -sha256 \
  | cut -d= -f2 | tr -d ':' | tr '[:upper:]' '[:lower:]')
if [[ "$fingerprint" != "$expected" ]]; then
  echo 'Signing certificate does not match the installed APK and RPK identity.' >&2
  exit 1
fi

./gradlew --no-daemon assembleRelease
apk=app/build/outputs/apk/release/app-release.apk
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Android SDK is required}}
apk_fingerprint=$("$JAVA_HOME/bin/java" -jar "$sdk/build-tools/35.0.0/lib/apksigner.jar" verify --print-certs "$apk" \
  | awk -F ': ' '/Signer #1 certificate SHA-256 digest/ {print $2}' | tr -d '\r')
if [[ "$apk_fingerprint" != "$expected" ]]; then
  echo 'Release APK signature verification failed.' >&2
  exit 1
fi

out="dist/release/v$version"
mkdir -p "$out"
filename="HFUT-Campus-Companion-$version.apk"
cp -- "$apk" "$out/$filename"
(cd "$out" && sha256sum -- "$filename" > SHA256SUMS.txt)
bash scripts/write_update_feed.sh "$out/$filename"

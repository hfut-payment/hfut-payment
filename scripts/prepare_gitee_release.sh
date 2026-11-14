#!/usr/bin/env bash
set -euo pipefail
tag=${1:?Release tag is required}
files=${2:?Release artifact directory is required}
repo=${GITHUB_REPOSITORY:?GitHub repository is required}
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]

release=$(gh release view "$tag" --repo "$repo" --json tagName,isDraft,isPrerelease)
jq -e --arg tag "$tag" '.tagName == $tag and .isDraft == false and .isPrerelease == false' <<<"$release" >/dev/null
latest=$(gh api "repos/$repo/releases/latest" --jq .tag_name)
if [[ "$latest" != "$tag" ]]; then
  printf '%s is not the current stable release (%s).\n' "$tag" "$latest" >&2
  exit 1
fi

commit=$(git rev-parse "refs/tags/$tag^{commit}")
git merge-base --is-ancestor "$commit" refs/remotes/origin/main
properties=$(git show "$commit:version.properties")
version=$(sed -n 's/^versionName=//p' <<<"$properties")
code=$(sed -n 's/^versionCode=//p' <<<"$properties")
[[ "v$version" == "$tag" && "$code" =~ ^[1-9][0-9]{0,9}$ && "$code" -le 2100000000 ]]

apk="HFUT-Campus-Companion-$version.apk"
mkdir -p -- "$files"
files=$(cd "$files" && pwd)
gh release download "$tag" --repo "$repo" --dir "$files" \
  --pattern "$apk" --pattern SHA256SUMS.txt --pattern update.json
sum=$(sha256sum "$files/$apk" | cut -d ' ' -f 1)
bytes=$(wc -c < "$files/$apk")
checksum_line=$(tr -d '\r' < "$files/SHA256SUMS.txt")
if [[ "$checksum_line" != "$sum  $apk" && "$checksum_line" != "$sum *$apk" ]]; then
  echo 'The published checksum does not match the APK.' >&2
  exit 1
fi
jq -e --arg version "$version" --arg apk "$apk" --arg sum "$sum" \
  --argjson code "$code" --argjson bytes "$((bytes))" \
  '.schema == 1 and .versionName == $version and .versionCode == $code and
   .apk == $apk and .sha256 == $sum and .bytes == $bytes' "$files/update.json" >/dev/null

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Android SDK is required}}
expected=$(git show "$commit:scripts/release-certificate.sha256" | tr -d '\r\n')
fingerprint=$("$JAVA_HOME/bin/java" -jar "$sdk/build-tools/35.0.0/lib/apksigner.jar" verify --print-certs "$files/$apk" \
  | awk -F ': ' '/Signer #1 certificate SHA-256 digest/ {print $2}' | tr -d '\r')
[[ "$expected" =~ ^[a-f0-9]{64}$ && "$fingerprint" == "$expected" ]]

band_commit=$(git show "$commit:band-source.sha" | tr -d '\r\n')
[[ "$band_commit" =~ ^[a-f0-9]{40}$ ]]
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
mkdir "$tmp/source"
git archive --format=tar --prefix="hfut-payment-$version/" "$commit" | tar -xf - -C "$tmp/source"
git init --quiet "$tmp/band"
git -C "$tmp/band" fetch --quiet --no-tags --depth=1 https://github.com/hfut-payment/hfut-payment-band.git "$band_commit"
git -C "$tmp/band" archive --format=tar --prefix="hfut-payment-band-$band_commit/" "$band_commit" \
  | tar -xf - -C "$tmp/source"
(
  cd "$tmp/source"
  export TZ=UTC
  find . -type f -print | LC_ALL=C sort \
    | zip -X -q -@ "$files/HFUT-Campus-Companion-$version-source.zip"
)

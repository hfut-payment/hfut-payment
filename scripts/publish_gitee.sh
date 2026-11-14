#!/usr/bin/env bash
set -euo pipefail
tag=${1:?Release tag is required}
files=${2:?Release artifact directory is required}
verify_only=${VERIFY_ONLY:-false}
: "${GITEE_REPOSITORY:?Set the GITEE_REPOSITORY repository variable}"
: "${GITEE_TOKEN:?Set the GITEE_TOKEN repository secret}"
[[ "$GITEE_REPOSITORY" =~ ^[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+$ ]]
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]

version=${tag#v}
assets=(SHA256SUMS.txt update.json "HFUT-Campus-Companion-$version-source.zip" "HFUT-Campus-Companion-$version.apk")
for asset in "${assets[@]}"; do
  [[ -s "$files/$asset" ]]
done

api="https://gitee.com/api/v5/repos/$GITEE_REPOSITORY"
http=(curl --silent --show-error --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 100)
read_http=("${http[@]}" --retry 2 --retry-delay 2 --retry-all-errors --retry-max-time 180)
printf 'Checking Gitee repository %s\n' "$GITEE_REPOSITORY"
branch=$("${read_http[@]}" --fail --get --data-urlencode "access_token=$GITEE_TOKEN" "$api" \
  | jq -er 'select(.private == false and .default_branch == "master") | .default_branch')
tmp=$(mktemp -d)
trap 'rm -f -- "$tmp/release.json" "$tmp/attachment" "$tmp/feed.json"; rmdir -- "$tmp"' EXIT
release_api="$api/releases"
title="payfor工大 $tag"
body="签名 Android 安装包、更新清单、校验文件和对应源码。GitHub 发行说明：https://github.com/$GITHUB_REPOSITORY/releases/tag/$tag"

status=$("${read_http[@]}" --get --data-urlencode "access_token=$GITEE_TOKEN" \
  --output "$tmp/release.json" --write-out '%{http_code}' "$release_api/tags/$tag")
if [[ "$status" == 404 ]] ||
   { [[ "$status" == 200 ]] && jq -e 'type == "null"' "$tmp/release.json" >/dev/null; }; then
  [[ "$verify_only" != true ]] || { echo 'The Gitee release does not exist.' >&2; exit 1; }
  "${http[@]}" --fail --output "$tmp/release.json" --request POST "$release_api" \
    --data-urlencode "access_token=$GITEE_TOKEN" --data-urlencode "tag_name=$tag" \
    --data-urlencode "name=$title" --data-urlencode "body=$body" \
    --data-urlencode "target_commitish=$branch" --data 'prerelease=true'
else
  [[ "$status" == 200 ]]
fi
id=$(jq -er '.id | numbers' "$tmp/release.json")
prerelease=$(jq -r '.prerelease' "$tmp/release.json")
[[ "$prerelease" == true || "$prerelease" == false ]]
if [[ "$verify_only" == true && "$prerelease" == true ]]; then
  echo 'The Gitee release is still a preview.' >&2
  exit 1
fi
attachments="$release_api/$id/attach_files"
download="https://gitee.com/$GITEE_REPOSITORY/releases/download/$tag"
native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -am "$1"; else printf '%s\n' "$1"; fi
}
list_attachments() {
  "${read_http[@]}" --fail --get --data-urlencode "access_token=$GITEE_TOKEN" "$attachments"
}
attachment_id() {
  jq -r --arg name "$1" '.[] | select(.name == $name or .filename == $name) | .id'
}
listed=$(list_attachments)
for asset in "${assets[@]}"; do
  printf 'Checking Gitee attachment %s\n' "$asset"
  attached=$(attachment_id "$asset" <<< "$listed")
  if [[ -z "$attached" ]]; then
    [[ "$prerelease" == true ]] || { printf 'Published Gitee release is missing %s.\n' "$asset" >&2; exit 1; }
    upload_timeout=100
    # The runner's route to Gitee can sustain only about 15 KiB/s for APKs.
    if [[ "$asset" == *.apk ]]; then upload_timeout=600; fi
    for attempt in 1 2; do
      printf 'Uploading %s (%s bytes), attempt %s\n' "$asset" "$(wc -c < "$files/$asset")" "$attempt"
      "${http[@]}" --max-time "$upload_timeout" --speed-limit 1024 --speed-time 45 \
        --http1.1 --request POST "$attachments" --header 'Expect:' --limit-rate 192k \
        --form "access_token=$GITEE_TOKEN" --form "file=@$files/$asset" \
        --output /dev/null --write-out 'Gitee upload HTTP %{response_code}, sent %{size_upload} bytes\n' || true
      # The server may save the upload even if its response is lost.
      listed=$(list_attachments)
      attached=$(attachment_id "$asset" <<< "$listed")
      if [[ -n "$attached" ]]; then break; fi
    done
    if [[ -z "$attached" ]]; then
      printf 'Gitee rejected %s. Rerun the mirror or use the saved release files.\n' "$asset" >&2
      exit 1
    fi
  fi
  [[ "$attached" =~ ^[0-9]+$ ]]
  printf 'Verifying anonymous download of %s\n' "$asset"
  "${read_http[@]}" --fail --location --output "$tmp/attachment" "$download/$asset"
  if [[ "$asset" == *-source.zip ]]; then
    "${PYTHON:-python3}" - "$(native_path "$files/$asset")" "$(native_path "$tmp/attachment")" <<'PY'
import sys
from zipfile import ZipFile

with ZipFile(sys.argv[1]) as expected, ZipFile(sys.argv[2]) as actual:
    names = sorted(expected.namelist())
    if names != sorted(actual.namelist()) or len(names) != len(set(names)):
        sys.exit("Gitee source archive differs from the tagged source.")
    for name in names:
        if expected.read(name) != actual.read(name):
            sys.exit(f"Gitee source archive content differs: {name}")
PY
  else
    cmp "$files/$asset" "$tmp/attachment"
  fi
done

require_latest() {
  latest=$(gh api "repos/$GITHUB_REPOSITORY/releases/latest" --jq .tag_name)
  if [[ "$latest" != "$tag" ]]; then
    printf 'A newer stable release is available on GitHub (%s).\n' "$latest" >&2
    exit 1
  fi
}

require_latest
if [[ "$prerelease" == true ]]; then
  printf 'Promoting Gitee release %s\n' "$tag"
  "${http[@]}" --fail --request PATCH "$release_api/$id" \
    --data-urlencode "access_token=$GITEE_TOKEN" --data-urlencode "tag_name=$tag" \
    --data-urlencode "name=$title" --data-urlencode "body=$body" \
    --data 'prerelease=false' | jq -e '.prerelease == false' >/dev/null
fi

contents="$api/contents/update.json"
feed_matches_release() {
  # The web editor may save the same JSON with CRLF line endings.
  cmp -s <(tr -d '\r' < "$1") "$files/update.json"
}
printf 'Checking Gitee static update feed\n'
status=$("${read_http[@]}" --get --data-urlencode "access_token=$GITEE_TOKEN" --data-urlencode "ref=$branch" \
  --output "$tmp/feed.json" --write-out '%{http_code}' "$contents")
method=POST
args=()
if [[ "$status" == 200 ]] && jq -e '. == [] or . == null' "$tmp/feed.json" >/dev/null; then
  # A missing Gitee path can return HTTP 200 with an empty result.
  method=POST
elif [[ "$status" == 200 ]]; then
  old_sha=$(jq -er '.sha | strings' "$tmp/feed.json")
  jq -er '.content' "$tmp/feed.json" | tr -d '\r\n' | base64 --decode > "$tmp/attachment"
  if feed_matches_release "$tmp/attachment"; then
    method=
  else
    current=$(jq -er '.versionCode | numbers' "$tmp/attachment")
    next=$(jq -er '.versionCode | numbers' "$files/update.json")
    [[ "$current" -lt "$next" ]] || { echo 'Refusing to roll back the public update feed.' >&2; exit 1; }
    method=PUT
    args=(--data-urlencode "sha=$old_sha")
  fi
elif [[ "$status" != 404 ]]; then
  printf 'Could not read Gitee update.json (HTTP %s).\n' "$status" >&2
  exit 1
fi
if [[ -n "$method" ]]; then
  [[ "$verify_only" != true ]] || { echo 'The Gitee update feed still needs publishing.' >&2; exit 1; }
  require_latest
  printf 'Publishing Gitee static update feed for %s\n' "$tag"
  "${http[@]}" --fail --request "$method" "$contents" \
    --data-urlencode "access_token=$GITEE_TOKEN" --data-urlencode "branch=$branch" \
    --data-urlencode "message=Publish Android update $tag" \
    --data-urlencode "content=$(base64 -w0 "$files/update.json")" "${args[@]}" >/dev/null
fi
raw="https://gitee.com/$GITEE_REPOSITORY/raw/$branch/update.json"
for attempt in 1 2 3; do
  if "${read_http[@]}" --fail --location --header 'Cache-Control: no-cache' --output "$tmp/attachment" "$raw" &&
     feed_matches_release "$tmp/attachment"; then break; fi
  if [[ "$attempt" == 3 ]]; then echo 'Public update feed still differs from the release.' >&2; exit 1; fi
  sleep 3
done
printf 'Verified Gitee release %s and its public update feed\n' "$tag"

#!/usr/bin/env bash
set -euo pipefail
tag=${1:?Release tag is required}
files=${2:?Release artifact directory is required}
: "${GITEE_REPOSITORY:?Set the GITEE_REPOSITORY repository variable}"
: "${GITEE_TOKEN:?Set the GITEE_TOKEN repository secret}"
[[ "$GITEE_REPOSITORY" =~ ^[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+$ ]]
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]

version=${tag#v}
assets=("HFUT-Campus-Companion-$version.apk" SHA256SUMS.txt update.json "HFUT-Campus-Companion-$version-source.zip")
for asset in "${assets[@]}"; do
  [[ -s "$files/$asset" ]]
done

api="https://gitee.com/api/v5/repos/$GITEE_REPOSITORY"
http=(curl --silent --show-error --proto '=https' --proto-redir '=https' --max-time 180)
branch=$("${http[@]}" --fail "$api" | jq -er 'select(.private == false) | .default_branch | strings | select(length > 0)')
tmp=$(mktemp -d)
trap 'rm -f -- "$tmp/release.json" "$tmp/attachment"; rmdir -- "$tmp"' EXIT
release_api="$api/releases"
title="payfor工大 $tag"
body="签名 Android 安装包、更新清单、校验文件和对应源码。GitHub 发行说明：https://github.com/$GITHUB_REPOSITORY/releases/tag/$tag"

status=$("${http[@]}" --output "$tmp/release.json" --write-out '%{http_code}' "$release_api/tags/$tag")
if [[ "$status" == 404 ]] ||
   { [[ "$status" == 200 ]] && jq -e 'type == "null"' "$tmp/release.json" >/dev/null; }; then
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
attachments="$release_api/$id/attach_files"
for asset in "${assets[@]}"; do
  attached=$("${http[@]}" --fail "$attachments" | jq -r --arg name "$asset" \
    '.[] | select(.name == $name or .filename == $name) | .id')
  if [[ -z "$attached" ]]; then
    [[ "$prerelease" == true ]] || { printf 'Published Gitee release is missing %s.\n' "$asset" >&2; exit 1; }
    "${http[@]}" --fail --request POST "$attachments" \
      --form "access_token=$GITEE_TOKEN" --form "file=@$files/$asset" >/dev/null
    attached=$("${http[@]}" --fail "$attachments" | jq -r --arg name "$asset" \
      '.[] | select(.name == $name or .filename == $name) | .id')
  fi
  [[ "$attached" =~ ^[0-9]+$ ]]
  "${http[@]}" --fail --location --output "$tmp/attachment" "$attachments/$attached/download"
  cmp "$files/$asset" "$tmp/attachment"
done

if [[ "$prerelease" == true ]]; then
  latest=$(gh api "repos/$GITHUB_REPOSITORY/releases/latest" --jq .tag_name)
  if [[ "$latest" != "$tag" ]]; then
    printf 'A newer stable release is available on GitHub (%s).\n' "$latest" >&2
    exit 1
  fi
  "${http[@]}" --fail --request PATCH "$release_api/$id" \
    --data-urlencode "access_token=$GITEE_TOKEN" --data-urlencode "tag_name=$tag" \
    --data-urlencode "name=$title" --data-urlencode "body=$body" \
    --data 'prerelease=false' | jq -e '.prerelease == false' >/dev/null
fi
printf 'Verified Gitee release %s\n' "$tag"

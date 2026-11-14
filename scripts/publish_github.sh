#!/usr/bin/env bash
set -euo pipefail
tag=${1:?Release tag is required}
files=${2:?Release artifact directory is required}
git merge-base --is-ancestor "$tag" refs/remotes/origin/main
apk="HFUT-Campus-Companion-${tag#v}.apk"
(cd "$files" && sha256sum -c SHA256SUMS.txt)
version=$(jq -er '.versionCode' "$files/update.json")
latest=$(gh api "repos/$GITHUB_REPOSITORY/releases/latest" --jq .tag_name 2>/dev/null || true)
if [[ -n "$latest" && "$latest" != "$tag" && "$tag" != *-* ]]; then
  latest_code=$(gh release download "$latest" --pattern update.json --output - --repo "$GITHUB_REPOSITORY" | jq -er '.versionCode')
  [[ "$version" -gt "$latest_code" ]] || { echo 'versionCode must increase' >&2; exit 1; }
fi
if ! gh release view "$tag" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  args=(--draft --verify-tag --generate-notes --title "payfor工大 $tag")
  if [[ "$tag" == *-* ]]; then args+=(--prerelease); fi
  gh release create "$tag" --repo "$GITHUB_REPOSITORY" "${args[@]}"
fi
release=$(gh release view "$tag" --repo "$GITHUB_REPOSITORY" --json isDraft,assets)
draft=$(jq -er '.isDraft | tostring' <<< "$release")
tmp=$(mktemp)
trap 'rm -f -- "$tmp"' EXIT
for asset in "$apk" SHA256SUMS.txt update.json; do
  if ! jq -e --arg name "$asset" '.assets | any(.name == $name)' <<< "$release" >/dev/null; then
    if [[ "$draft" != true ]]; then
      echo 'A published release is incomplete. Inspect it before retrying.' >&2
      exit 1
    fi
    gh release upload "$tag" --repo "$GITHUB_REPOSITORY" "$files/$asset"
  fi
  gh release download "$tag" --repo "$GITHUB_REPOSITORY" --pattern "$asset" --output - > "$tmp"
  cmp "$files/$asset" "$tmp"
done
if [[ "$draft" == true ]]; then
  printf 'Release draft %s is ready for review. Publish it from GitHub Releases.\n' "$tag"
fi

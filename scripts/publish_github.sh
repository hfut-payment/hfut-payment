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
draft=$(gh release view "$tag" --repo "$GITHUB_REPOSITORY" --json isDraft --jq .isDraft)
for asset in "$apk" SHA256SUMS.txt update.json; do
  if gh release view "$tag" --repo "$GITHUB_REPOSITORY" --json assets --jq '.assets[].name' | grep -Fxq "$asset"; then
    gh release download "$tag" --repo "$GITHUB_REPOSITORY" --pattern "$asset" --output - | cmp "$files/$asset" -
  elif [[ "$draft" == true ]]; then
    gh release upload "$tag" --repo "$GITHUB_REPOSITORY" "$files/$asset"
  else
    echo 'A published release is incomplete. Inspect it before retrying.' >&2
    exit 1
  fi
done
if [[ "$draft" == true ]]; then
  if [[ "$tag" == *-* ]]; then
    gh release edit "$tag" --repo "$GITHUB_REPOSITORY" --draft=false --prerelease --latest=false
  else
    gh release edit "$tag" --repo "$GITHUB_REPOSITORY" --draft=false --latest
  fi
fi

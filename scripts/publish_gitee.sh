#!/usr/bin/env bash
set -euo pipefail
tag=${1:?Release tag is required}
files=${2:?Release artifact directory is required}
: "${GITEE_REPOSITORY:?Set GITEE_REPOSITORY}"
: "${GITEE_USERNAME:?Set GITEE_USERNAME}"
: "${GITEE_TOKEN:?Set GITEE_TOKEN}"
[[ "$GITEE_REPOSITORY" =~ ^[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+$ ]]
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9.-]+)?$ ]]
apk="HFUT-Campus-Companion-${tag#v}.apk"
(cd "$files" && sha256sum -c SHA256SUMS.txt)

tmp=$(mktemp -d)
trap 'rm -f -- "$tmp/askpass" "$tmp/release.json" "$tmp/attachment"; rmdir -- "$tmp"' EXIT
cat > "$tmp/askpass" <<'EOF'
#!/bin/sh
case "$1" in
  *Username*) printf '%s\n' "$GITEE_USERNAME" ;;
  *) printf '%s\n' "$GITEE_TOKEN" ;;
esac
EOF
chmod 700 "$tmp/askpass"
git merge-base --is-ancestor "$tag" refs/remotes/origin/main
GIT_TERMINAL_PROMPT=0 GIT_ASKPASS="$tmp/askpass" git -c credential.helper= push \
  "https://gitee.com/$GITEE_REPOSITORY.git" "HEAD:refs/heads/main" "refs/tags/$tag:refs/tags/$tag"

api="https://gitee.com/api/v5/repos/$GITEE_REPOSITORY/releases"
http=(curl --silent --show-error --proto '=https' --proto-redir '=https' --max-time 120)
status=$("${http[@]}" --output "$tmp/release.json" --write-out '%{http_code}' "$api/tags/$tag")
if [[ "$status" == 404 ]]; then
  "${http[@]}" --fail --output "$tmp/release.json" --request POST "$api" \
    --data-urlencode "access_token=$GITEE_TOKEN" --data-urlencode "tag_name=$tag" \
    --data-urlencode "name=payfor工大 $tag" --data-urlencode "body=已签名的 Android 安装包及 SHA-256 校验文件。" \
    --data-urlencode "target_commitish=$(git rev-parse HEAD)" --data 'prerelease=true'
else
  [[ "$status" == 200 ]]
fi
id=$(jq -er '.id | numbers' "$tmp/release.json")
for asset in "$apk" SHA256SUMS.txt update.json; do
  attached=$("${http[@]}" --fail "$api/$id/attach_files" | jq -r --arg name "$asset" \
    '.[] | select(.name == $name or .filename == $name) | .id')
  if [[ -z "$attached" ]]; then
    "${http[@]}" --fail --request POST "$api/$id/attach_files" \
      --form "access_token=$GITEE_TOKEN" --form "file=@$files/$asset" >/dev/null
    attached=$("${http[@]}" --fail "$api/$id/attach_files" | jq -r --arg name "$asset" \
      '.[] | select(.name == $name or .filename == $name) | .id')
  fi
  [[ "$attached" =~ ^[0-9]+$ ]]
  "${http[@]}" --fail --location --output "$tmp/attachment" "$api/$id/attach_files/$attached/download"
  cmp "$files/$asset" "$tmp/attachment"
done
if [[ "$tag" != *-* ]]; then
  "${http[@]}" --fail --request PATCH "$api/$id" \
    --data-urlencode "access_token=$GITEE_TOKEN" --data-urlencode "tag_name=$tag" \
    --data-urlencode "name=payfor工大 $tag" --data-urlencode "body=已签名的 Android 安装包及 SHA-256 校验文件。" \
    --data 'prerelease=false' | jq -e '.prerelease == false' >/dev/null
fi

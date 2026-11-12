#!/usr/bin/env bash
set -euo pipefail

for loc in C.UTF-8 en_US.UTF-8 C.utf8 en_US.utf8; do
  if locale -a 2>/dev/null | grep -x "$loc" >/dev/null; then
    export LC_ALL=$loc
    break
  fi
done

types='feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert'
header_max=72
line_max=100

lines=()
while IFS= read -r line; do
  [[ "$line" =~ ^#\ -+\ \>8\ -+$ ]] && break
  [[ "$line" =~ ^# ]] && continue
  [[ "$line" =~ ^[[:space:]]*$ ]] && continue
  lines+=("$line")
done < "$1"
header="${lines[0]:-}"

if [[ "$header" =~ ^(Merge\ |Revert\ \"|fixup!\ |squash!\ |amend!\ ) ]]; then
  exit 0
fi

fail() {
  printf '提交信息不合规：%s\n  %s\n' "$1" "$header" >&2
  exit 1
}

length=$(printf '%s' "$header" | wc -m | tr -d ' ')
((length <= header_max)) || fail "标题超过 ${header_max} 个字符（当前 ${length}）"
[[ "$header" =~ ^($types)(\([^()A-Z[:space:]][^()A-Z]*\))?!?:\ [^[:space:]](.*[^.[:space:]])?$ ]] \
  || fail "标题须为 \`<type>(<scope>): <subject>\`，type 取 ${types//|/, }，结尾不加句点"
for line in "${lines[@]:1}"; do
  len=$(printf '%s' "$line" | wc -m | tr -d ' ')
  ((len <= line_max)) || fail "正文每行不超过 ${line_max} 个字符：${line:0:30}…"
done

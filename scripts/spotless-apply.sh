#!/usr/bin/env bash
set -euo pipefail

[ $# -eq 0 ] && exit 0
root="$(git rev-parse --show-toplevel)"
cd "$root"
escape() { printf '%s' "$1" | sed 's/[.[\*^$()+?{|]/\\&/g'; }
patterns=()
for f in "$@"; do
  patterns+=("^$(escape "$root/$f")$")
done
IFS=,
./mvnw -q spotless:apply -DspotlessFiles="${patterns[*]}"

#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="${FASTAPI_DIR:-$root/../fastapi-temp}"
ref="${FASTAPI_REF:-HEAD}"
files=(
  openapi/SOURCE
  openapi/nestjs-baseline.json
  openapi/validation.json
  openapi/nest_handlers.json
  db/nestjs-schema.sql
  tests/contract/fixtures/validation-cases.json
  scripts/openapi-sync.sh
  scripts/schema_snapshot.py
  scripts/gen_validation_cases.py
  scripts/gen_nest_handlers.py
  scripts/nestjs/validation-cases.ts
  scripts/nestjs/nestjs.compose.yaml
)
for f in "${files[@]}"; do
  mkdir -p "$root/$(dirname "$f")"
  git -C "$src" show "$ref:$f" >"$root/$f"
done
chmod +x "$root/scripts/openapi-sync.sh"
echo "已从 $src@$ref 复制 ${#files[@]} 个共享文件"

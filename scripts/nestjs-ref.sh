#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
nestjs_dir="${NESTJS_DIR:-$root/../nestjs-template}"
commit="$(sed -n 's/^commit=//p' "$root/openapi/SOURCE")"
export NESTJS_PORT="${NESTJS_PORT:-3021}"
export NESTJS_IMAGE="${NESTJS_IMAGE:-fastapi-temp/nestjs-ref:${commit:0:12}}"
state="$root/target/nestjs-ref"
export NESTJS_ENV_FILE="$state/env-example-relational"
compose=(docker compose -p springboot-template-nestjs -f "$root/scripts/nestjs/nestjs.compose.yaml")

case "${1:-}" in
  up)
    mkdir -p "$state"
    git -C "$nestjs_dir" show "$commit:env-example-relational" \
      | sed -e 's/^THROTTLE_ENABLED=.*/THROTTLE_ENABLED=true/' -e 's/^TRUST_PROXY=.*/TRUST_PROXY=1/' >"$NESTJS_ENV_FILE"
    if ! docker image inspect "$NESTJS_IMAGE" >/dev/null 2>&1; then
      echo "缺少镜像 $NESTJS_IMAGE，先执行 make openapi-sync 构建" >&2
      exit 1
    fi
    "${compose[@]}" up -d --wait postgres redis
    "${compose[@]}" up -d api
    for _ in $(seq 1 90); do
      if curl -sf -o /dev/null "http://localhost:${NESTJS_PORT}/"; then
        echo "nestjs 已就绪：http://localhost:${NESTJS_PORT}"
        exit 0
      fi
      sleep 2
    done
    "${compose[@]}" logs api | tail -50
    exit 1
    ;;
  down)
    mkdir -p "$state"
    touch "$NESTJS_ENV_FILE"
    "${compose[@]}" down -v --remove-orphans
    ;;
  *)
    echo "用法：$0 up|down" >&2
    exit 2
    ;;
esac

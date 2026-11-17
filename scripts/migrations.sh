#!/usr/bin/env bash
# 在临时 Postgres 容器上校验迁移：APP_ROLE=migrate → flyway validate → 结构快照与 db/nestjs-schema.sql 比对 → flyway clean → 再次 migrate，快照与第一次相同。
# smoke：只对空库执行一次 APP_ROLE=migrate（build 门禁）。
#
# 变量：MIGRATIONS_PG_IMAGE（默认与 docker/infra.compose.yaml 相同）、APP_JAR（默认 template-app/target 下的可执行 jar）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-check}"
IMAGE="${MIGRATIONS_PG_IMAGE:-$(sed -n 's/^ *image: *\(postgres:.*\)$/\1/p' "$ROOT/docker/infra.compose.yaml" | head -1)}"
JAR="${APP_JAR:-$(find "$ROOT/template-app/target" -maxdepth 1 -name 'template-app-*.jar' ! -name '*-plain.jar' | head -1)}"
TABLES=(user role status session permission role_permission file audit_logs config region
  notification notification_setting address favorite feedback user_consent account_cleanup admin_alert)
CONTAINER="springboot-template-migrations-$$"
STAGE="$(mktemp -d)"

cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$STAGE"
}
trap cleanup EXIT

[[ -n "$JAR" && -f "$JAR" ]] || { echo "缺少 template-app 可执行 jar，先执行 ./mvnw -DskipTests package" >&2; exit 1; }

docker run -d --name "$CONTAINER" -e POSTGRES_USER=root -e POSTGRES_PASSWORD=secret -e POSTGRES_DB=api \
  -p 127.0.0.1::5432 "$IMAGE" >/dev/null
for i in $(seq 1 60); do
  docker exec "$CONTAINER" pg_isready -q -h 127.0.0.1 -U root -d api && break
  [[ $i -eq 60 ]] && { docker logs "$CONTAINER" >&2; exit 1; }
  sleep 1
done
PORT="$(docker port "$CONTAINER" 5432/tcp | head -1 | sed 's/.*://')"
DSN="postgresql://root:secret@127.0.0.1:${PORT}/api"

migrate() {
  echo "==> APP_ROLE=migrate"
  env APP_ROLE=migrate APP_ENV=development REDIS_ENABLED=false \
    AUTH_JWT_SECRET=migrations AUTH_REFRESH_SECRET=migrations AUTH_FORGOT_SECRET=migrations AUTH_CONFIRM_EMAIL_SECRET=migrations \
    DATABASE_HOST=127.0.0.1 DATABASE_PORT="$PORT" DATABASE_USERNAME=root DATABASE_PASSWORD=secret DATABASE_NAME=api \
    java -jar "$JAR"
}

flyway() {
  echo "==> flyway $1"
  "$ROOT/mvnw" -q -f "$ROOT/ci/flyway/pom.xml" "flyway:$1" \
    -Dflyway.url="jdbc:postgresql://127.0.0.1:${PORT}/api" -Dflyway.user=root -Dflyway.password=secret
}

snapshot() {
  uv run --quiet --script "$ROOT/scripts/schema_snapshot.py" "$DSN" "${TABLES[@]}" >"$1"
}

migrate
[[ "$MODE" == smoke ]] && exit 0

flyway validate

echo "==> 结构快照比对"
snapshot "$STAGE/first.sql"
missing="$(comm -23 <(sort "$ROOT/db/nestjs-schema.sql") <(sort "$STAGE/first.sql") | grep -vE '^constraint feedback\.FK_feedback_(errata|question) ' || true)"
extra="$(comm -13 <(sort "$ROOT/db/nestjs-schema.sql") <(sort "$STAGE/first.sql"))"
if [[ -n "$missing$extra" ]]; then
  printf '仅 db/nestjs-schema.sql:\n%s\n仅迁移结果:\n%s\n' "$missing" "$extra" >&2
  exit 1
fi

flyway clean
left="$(docker exec "$CONTAINER" psql -qAt -U root -d api -c "SELECT tablename FROM pg_tables WHERE schemaname = 'public'")"
[[ -z "$left" ]] || { echo "flyway clean 后 public 仍有表：$left" >&2; exit 1; }

migrate
snapshot "$STAGE/second.sql"
diff -u "$STAGE/first.sql" "$STAGE/second.sql"
echo "==> 迁移校验通过"

#!/bin/bash

# Spring Boot API 容器管理脚本
# 用法: ./manage.sh [start|stop|restart|remove|status|logs|health|cleanup|help]

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

CONTAINER_NAME="springboot-api"
COMPOSE_FILE="docker/docker-compose.ecs.yaml"
HEALTH_URL="http://localhost:3000/api/v1/health/ready"
HEALTH_TIMEOUT=30

log_info() { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }
log_blue() { echo -e "${BLUE}[INFO]${NC} $*"; }

check_docker() {
    if ! command -v docker >/dev/null 2>&1; then
        log_error "Docker is not installed. Please install Docker first."
        exit 1
    fi
    if ! docker compose version >/dev/null 2>&1; then
        log_error "Docker Compose is not available. Please install Docker Compose."
        exit 1
    fi
}

# 服务器与仓库布局相同：<root>/deploy/manage.sh、<root>/docker/、<root>/.env
get_project_root() {
    cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd
}

compose() {
    local project_root
    project_root=$(get_project_root)
    (
        cd "$project_root"
        local env_files=(--env-file .env)
        if [ -f .env.image ]; then
            env_files+=(--env-file .env.image)
        fi
        docker compose "${env_files[@]}" -f "$COMPOSE_FILE" "$@"
    )
}

# 读取部署目录 .env 中的变量（不展开引用与转义）
env_value() {
    local name="$1" default="$2" value
    value=$(sed -n "s/^${name}=//p" "$(get_project_root)/.env" | tail -n 1)
    value="${value%\"}"
    value="${value#\"}"
    echo "${value:-$default}"
}

container_running() {
    docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"
}

container_exists() {
    docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"
}

api_ready() {
    compose exec -T api java -cp /app/probe HealthProbe "$HEALTH_URL" >/dev/null 2>&1
}

run_migrations() {
    log_info "Running database migrations..."
    if ! compose run --rm migrate; then
        log_error "Database migration failed"
        exit 1
    fi

    if [ "$(env_value RUN_SEEDS false)" = "true" ]; then
        log_info "Running seeds (RUN_SEEDS=true)..."
        if ! compose run --rm seed; then
            log_error "Seeding failed"
            exit 1
        fi
    fi
}

start_services() {
    if container_running; then
        log_warn "Services are already running"
        show_status
        return 0
    fi

    run_migrations

    log_info "Starting services..."
    compose up -d --remove-orphans

    log_info "Waiting for API to be healthy..."
    for i in $(seq 1 "$HEALTH_TIMEOUT"); do
        if api_ready; then
            log_info "API is healthy!"
            show_status
            return 0
        fi
        echo -ne "  Attempt $i/$HEALTH_TIMEOUT...\r"
        sleep 5
    done

    log_error "API health check failed after $HEALTH_TIMEOUT attempts"
    show_logs_tail
    exit 1
}

stop_services() {
    if ! container_exists; then
        log_warn "No services running"
        return 0
    fi

    log_info "Stopping services..."
    compose stop
    log_info "Services stopped"
}

restart_services() {
    log_info "Restarting services..."
    compose restart

    sleep 3

    if container_running; then
        log_info "Services restarted successfully"
        show_status
    else
        log_error "Services failed to restart"
        show_logs_tail
        exit 1
    fi
}

remove_services() {
    log_info "Removing services and volumes..."
    compose --profile seed down -v --remove-orphans
    log_info "Services removed"
}

show_status() {
    log_blue "Service Status:"
    compose ps
}

show_logs() {
    log_info "Showing logs (press Ctrl+C to exit)..."
    compose logs -f
}

show_logs_tail() {
    log_blue "Recent API logs:"
    compose logs api worker --tail=30
}

health_check() {
    if container_running; then
        if api_ready; then
            log_info "API is healthy"
        else
            log_error "API is running but health check failed"
            exit 1
        fi
    else
        log_error "API container is not running"
        exit 1
    fi
}

# 按创建时间保留最新 3 个镜像
cleanup_images() {
    local image_pattern="${1:-}"

    if [ -z "$image_pattern" ]; then
        log_error "Usage: $0 cleanup <image-pattern>"
        exit 1
    fi

    log_info "Cleaning up old images for pattern: ${image_pattern}"
    local old_images
    old_images=$(docker images "$image_pattern" --format '{{.CreatedAt}}	{{.ID}}' \
        | sort -r | awk -F '\t' '!seen[$2]++ { print $2 }' | tail -n +4)

    if [ -n "$old_images" ]; then
        echo "$old_images" | xargs docker rmi -f || true
        log_info "Old images cleaned up"
    else
        log_info "No old images to clean up"
    fi
}

show_help() {
    echo -e "${BLUE}Spring Boot API Container Management Script${NC}

Usage: $0 [COMMAND]

${GREEN}Commands:${NC}
  start       Run migrations (and seeds when RUN_SEEDS=true), then start all services (api, worker, postgres, redis)
  stop        Stop all running services
  restart     Restart all services
  remove      Remove all services and volumes
  status      Show services status
  logs        Show and follow service logs
  health      Run API health check
  cleanup     Clean up old Docker images (keep latest 3)
  help        Show this help message

${GREEN}Examples:${NC}
  $0 start              # Start all services
  $0 stop               # Stop all services
  $0 restart            # Restart all services
  $0 logs               # View logs (follow mode)
  $0 health             # Check API health
  $0 cleanup ghcr.io/xingyunqi-tech/springboot-template    # Clean old images

${GREEN}Configuration:${NC}
  Container:    $CONTAINER_NAME
  Compose file: $COMPOSE_FILE
  Health probe: docker compose exec -T api java -cp /app/probe HealthProbe $HEALTH_URL
"
}

main() {
    check_docker

    local command="${1:-help}"

    case "$command" in
        start)
            start_services
            ;;
        stop)
            stop_services
            ;;
        restart)
            restart_services
            ;;
        remove|rm)
            remove_services
            ;;
        status|ps)
            show_status
            ;;
        logs)
            show_logs
            ;;
        health)
            health_check
            ;;
        cleanup)
            cleanup_images "${2:-}"
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            log_error "Unknown command: $command"
            echo ""
            show_help
            exit 1
            ;;
    esac
}

main "$@"

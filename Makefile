.DEFAULT_GOAL := help
SHELL := /bin/bash

-include .env
export

MVNW := ./mvnw
NESTJS_PORT ?= 3021
COMPOSE_ENV := $(if $(wildcard .env),--env-file .env)
APP_URL ?= http://localhost:3000
PARITY_TARGETS ?= java=http://localhost:3000,nestjs=http://localhost:$(NESTJS_PORT)

.PHONY: help
help: ## 列出可用目标
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z_-]+:.*##/ {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

.PHONY: install
install: ## 安装 Git 钩子（prek）
	prek install

.PHONY: dev
dev: ## 本地启动 api（端口 APP_PORT，默认 3000；依赖 make infra）
	$(MVNW) -pl template-app -am -DskipTests compile spring-boot:run

.PHONY: fmt
fmt: ## 格式化 Java、pom、Markdown 与其它文本
	$(MVNW) spotless:apply

.PHONY: check
check: ## spotless:check + 编译（Error Prone / NullAway）+ Checkstyle + 单元测试 + ArchUnit
	$(MVNW) spotless:check test

.PHONY: test
test: ## 单元测试（含 ArchUnit）
	$(MVNW) test

.PHONY: it
it: ## 单元 + 集成测试（Testcontainers，需要 Docker）+ 聚合覆盖率
	$(MVNW) verify

.PHONY: test-contract
test-contract: ## 契约测试：/docs-json、handler 覆盖、校验执行器 4516 条 oracle 用例、共享产物（UPDATE_CONTRACT_DOC=1 重新生成 docs/contract.md）
	$(MVNW) verify -Dgroups=contract -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false

.PHONY: test-e2e
test-e2e: ## E2E 测试（APP_URL，默认 http://localhost:3000）
	$(MVNW) -Pe2e -pl tests/e2e -am verify -DAPP_URL=$(APP_URL)

.PHONY: test-parity
test-parity: ## parity 测试（PARITY_TARGETS，默认 java=3000、nestjs=NESTJS_PORT；本服务须与 nestjs 同 APP_NAME 启动，并设 THROTTLE_ENABLED=true、TRUST_PROXY=1）
	$(MVNW) -Pparity -pl tests/parity -am verify -DPARITY_TARGETS=$(PARITY_TARGETS)

.PHONY: module
module: ## 按 OpenAPI 标签生成模块骨架（NAME=orders TAG=Orders）：写入 scope.yaml，生成 controller 桩（501）、service、repository
	@test -n "$(NAME)" && test -n "$(TAG)" || { echo "usage: make module NAME=orders TAG=Orders" >&2; exit 2; }
	$(MVNW) -q -pl tools/generator -am process-classes -Dmodulegen.skip=false -Dmodulegen.step=scope '-Dmodulegen.name=$(NAME)' '-Dmodulegen.tag=$(TAG)'
	$(MVNW) -q -pl tools/generator -am process-classes -Dmodulegen.skip=false -Dmodulegen.step=scaffold '-Dmodulegen.name=$(NAME)' '-Dmodulegen.tag=$(TAG)'
	$(MVNW) -q -pl template-app spotless:apply

.PHONY: infra
infra: ## 启动本地 Postgres / Redis / Mailpit（docker compose）
	docker compose -f docker/infra.compose.yaml $(COMPOSE_ENV) up -d --wait

.PHONY: openapi-sync
openapi-sync: ## 在 nestjs 固定提交上重新导出共享产物（需要 Docker 与 uv）
	scripts/openapi-sync.sh

.PHONY: openapi-copy
openapi-copy: ## 从同级 fastapi-temp 的已提交版本复制共享产物与脚本（FASTAPI_DIR、FASTAPI_REF，默认 HEAD）
	scripts/openapi-copy.sh

.PHONY: nestjs-up
nestjs-up: ## 启动 nestjs 参照服务（openapi/SOURCE 的提交，端口 NESTJS_PORT，默认 3021，THROTTLE_ENABLED=true、TRUST_PROXY=1），供 parity 使用
	scripts/nestjs-ref.sh up

.PHONY: nestjs-down
nestjs-down: ## 停止并清理 nestjs 参照服务
	scripts/nestjs-ref.sh down

.PHONY: ci
ci: ## 与 CI 相同的门禁
	$(MVNW) -Pci spotless:check verify

.PHONY: clean
clean: ## 清理构建产物
	$(MVNW) clean

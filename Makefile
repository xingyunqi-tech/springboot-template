.DEFAULT_GOAL := help
SHELL := /bin/bash

-include .env
export

MVNW := ./mvnw
COMPOSE_ENV := $(if $(wildcard .env),--env-file .env)
APP_URL ?= http://localhost:3000
PARITY_TARGETS ?= java=http://localhost:3000,nestjs=http://localhost:3001

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

.PHONY: test-e2e
test-e2e: ## E2E 测试（APP_URL，默认 http://localhost:3000）
	$(MVNW) -Pe2e -pl tests/e2e -am verify -DAPP_URL=$(APP_URL)

.PHONY: test-parity
test-parity: ## parity 测试（PARITY_TARGETS，默认 java=3000、nestjs=3001）
	$(MVNW) -Pparity -pl tests/parity -am verify -DPARITY_TARGETS=$(PARITY_TARGETS)

.PHONY: infra
infra: ## 启动本地 Postgres / Redis / Mailpit（docker compose）
	docker compose -f docker/infra.compose.yaml $(COMPOSE_ENV) up -d --wait

.PHONY: ci
ci: ## 与 CI 相同的门禁
	$(MVNW) -Pci spotless:check verify

.PHONY: clean
clean: ## 清理构建产物
	$(MVNW) clean

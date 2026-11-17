.DEFAULT_GOAL := help
SHELL := /bin/bash

-include .env
export

MVNW := ./mvnw
DEV_RUN := $(MVNW) -pl template-app -am -DskipTests compile spring-boot:run
NESTJS_PORT ?= 3021
COMPOSE_ENV := $(if $(wildcard .env),--env-file .env)
APP_URL ?= http://localhost:$(or $(APP_PORT),3000)
MAILPIT_URL ?= http://localhost:$(or $(MAIL_CLIENT_PORT),8025)
IMAGE ?= springboot-template:local
E2E_COMPOSE := docker compose -f docker/docker-compose.base.yaml -f docker/docker-compose.postgres.yaml -f docker/docker-compose.redis.yaml -f docker/docker-compose.ci.yaml
PARITY_TARGETS ?= java=http://localhost:3000,nestjs=http://localhost:$(NESTJS_PORT)

# renovate: datasource=pypi depName=typos
TYPOS_VERSION := 1.52.2
ACTIONLINT_IMAGE := rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667
ZIZMOR_IMAGE := ghcr.io/zizmorcore/zizmor:1.32.0@sha256:3014cc8f68f663935f69b3c3e0e41f5ce102b268d97db13db0f62208747f860d
HADOLINT_IMAGE := hadolint/hadolint:v2.15.1@sha256:32dac94127fd60b7b7e3fbfc65e1383b9b5e25c9bfd7b8536de7a539fe68a12d
GITLEAKS_IMAGE := ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f
TRIVY_IMAGE := aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb
TRIVY := docker run --rm -v springboot-template-trivy-cache:/root/.cache/trivy
GITLEAKS_LOG_OPTS ?=

.PHONY: help
help: ## 列出可用目标
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z0-9_-]+:.*##/ {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

.PHONY: install
install: ## 安装 Git 钩子（prek）
	prek install

.PHONY: dev
dev: ## 本地启动 api（读取 .env；端口 APP_PORT，默认 3000；依赖 make infra）
	$(DEV_RUN)

.PHONY: migrate
migrate: ## Flyway 迁移 + JobRunr 建表后退出（读取 .env；DB_SCHEMA_OWNER=external 时不跑 Flyway，只校验表结构）
	APP_ROLE=migrate $(DEV_RUN)

.PHONY: seed
seed: ## 写入种子数据后退出（读取 .env）
	APP_ROLE=seed $(DEV_RUN)

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
test-e2e: ## E2E 测试（APP_URL，默认 http://localhost:3000；MAILPIT_URL，默认 http://localhost:8025）
	$(MVNW) -Pe2e -pl tests/e2e -am verify -DAPP_URL=$(APP_URL) -DMAILPIT_URL=$(MAILPIT_URL)

.PHONY: e2e-up
e2e-up: ## 构建镜像，以 ci 组合（base + postgres + redis + ci）依次起 migrate → seed → api + worker + Mailpit，输出 APP_URL / MAILPIT_URL（宿主端口取 APP_PORT / MAIL_CLIENT_PORT，默认 3000 / 8025）
	$(E2E_COMPOSE) up -d --build --wait --wait-timeout 300 || { $(E2E_COMPOSE) logs --tail 200; $(E2E_COMPOSE) down -v --remove-orphans; exit 1; }
	@echo "APP_URL=http://$$($(E2E_COMPOSE) port api 3000) MAILPIT_URL=http://$$($(E2E_COMPOSE) port mailpit 8025)"

.PHONY: e2e-down
e2e-down: ## 停止 ci 组合并删除卷
	$(E2E_COMPOSE) down -v --remove-orphans

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

.PHONY: docker-build
docker-build: ## 构建生产镜像（IMAGE，默认 springboot-template:local）
	docker build -t $(IMAGE) .

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

.PHONY: lint
lint: ## spotless:check、enforcer、Checkstyle、typos
	$(MVNW) spotless:check validate
	uvx typos@$(TYPOS_VERSION)

.PHONY: hadolint
hadolint: ## hadolint 检查 Dockerfile（需要 Docker）
	docker run --rm -i $(HADOLINT_IMAGE) < Dockerfile

.PHONY: workflow-lint
workflow-lint: ## actionlint、zizmor（门槛 medium；设置 GH_TOKEN 时启用在线审计；需要 Docker）
	docker run --rm -v "$(CURDIR):/repo" -w /repo $(ACTIONLINT_IMAGE) -color
	docker run --rm -v "$(CURDIR):/repo:ro" -w /repo -e GH_TOKEN $(ZIZMOR_IMAGE) --min-severity medium .

.PHONY: type-check
type-check: ## javac + Error Prone + NullAway（main 与 test 源码，-Pci）
	$(MVNW) -Pci test-compile

.PHONY: test-ci
test-ci: ## -Pci verify：单元 + ArchUnit + 集成 + 契约，JaCoCo 聚合阈值（行 ≥ 80%、分支 ≥ 70%）与测试数量下限（ci/test-floor.properties）
	$(MVNW) -Pci verify

.PHONY: migrations
migrations: ## 临时 Postgres 上 migrate → flyway validate → 结构快照比对 → flyway clean → migrate（需要 Docker 与 uv）
	$(MVNW) -q -pl template-app -am -DskipTests package
	scripts/migrations.sh

.PHONY: vuln
vuln: ## trivy fs 扫描 Maven 依赖（HIGH / CRITICAL；优先读取本机 ~/.m2；需要 Docker）
	$(TRIVY) -v "$(HOME)/.m2:/root/.m2:ro" -v "$(CURDIR):/src:ro" $(TRIVY_IMAGE) fs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --no-progress /src

.PHONY: gitleaks
gitleaks: ## gitleaks 扫描 Git 历史（GITLEAKS_LOG_OPTS 限定提交范围，如 origin/main..HEAD；需要 Docker）
	docker run --rm -v "$(CURDIR):/repo" $(GITLEAKS_IMAGE) git /repo --no-banner --redact $(if $(GITLEAKS_LOG_OPTS),--log-opts="$(GITLEAKS_LOG_OPTS)")

.PHONY: security
security: vuln gitleaks ## trivy fs + gitleaks

.PHONY: build
build: ## 打包（-Pci，含 CycloneDX SBOM），可执行 jar 对空库执行 APP_ROLE=migrate 冒烟（需要 Docker）
	$(MVNW) -Pci -DskipTests package
	scripts/migrations.sh smoke

.PHONY: image-scan
image-scan: docker-build ## Trivy 扫描生产镜像 IMAGE（HIGH / CRITICAL，仅报告有修复版本的漏洞）
	$(TRIVY) -v /var/run/docker.sock:/var/run/docker.sock $(TRIVY_IMAGE) \
		image --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed --no-progress $(IMAGE)

.PHONY: e2e
e2e: ## e2e-up → test-e2e → e2e-down
	$(MAKE) e2e-up
	$(MAKE) test-e2e || { $(E2E_COMPOSE) logs --tail 200; $(MAKE) e2e-down; exit 1; }
	$(MAKE) e2e-down

.PHONY: ci
ci: lint hadolint workflow-lint type-check test-ci migrations test-contract security build image-scan e2e ## 本地运行 check.yml 中除 parity 外的全部 job

.PHONY: clean
clean: ## 清理构建产物
	$(MVNW) clean

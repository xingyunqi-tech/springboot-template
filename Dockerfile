# syntax=docker/dockerfile:1

FROM eclipse-temurin:25.0.5_8-jdk-noble AS builder
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
COPY coverage-report/pom.xml coverage-report/
COPY template-app/pom.xml template-app/
COPY template-common/pom.xml template-common/
COPY template-common/template-common-core/pom.xml template-common/template-common-core/
COPY template-common/template-common-json/pom.xml template-common/template-common-json/
COPY template-common/template-common-mail/pom.xml template-common/template-common-mail/
COPY template-common/template-common-mybatis/pom.xml template-common/template-common-mybatis/
COPY template-common/template-common-observability/pom.xml template-common/template-common-observability/
COPY template-common/template-common-oss/pom.xml template-common/template-common-oss/
COPY template-common/template-common-redis/pom.xml template-common/template-common-redis/
COPY template-common/template-common-security/pom.xml template-common/template-common-security/
COPY template-common/template-common-web/pom.xml template-common/template-common-web/
COPY template-contract/pom.xml template-contract/
COPY template-views/pom.xml template-views/
COPY tools/generator/pom.xml tools/generator/
COPY tools/openapi-tools/pom.xml tools/openapi-tools/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -pl template-app -am dependency:go-offline -DexcludeGroupIds=tech.xingyunqi.template
COPY openapi/ openapi/
COPY db/nestjs-schema.sql db/
COPY tools/openapi-tools/ tools/openapi-tools/
COPY template-common/ template-common/
COPY template-views/ template-views/
COPY template-contract/ template-contract/
COPY template-app/ template-app/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -pl template-app -am -DskipTests -Dcheckstyle.skip -Djacoco.skip package \
    && mkdir -p /out && cp template-app/target/template-app-*.jar /out/app.jar
COPY docker/HealthProbe.java /tmp/probe/
RUN javac --release 25 -d /out/probe /tmp/probe/HealthProbe.java

FROM eclipse-temurin:25.0.5_8-jre-noble AS extract
WORKDIR /extract
COPY --from=builder /out/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination layers

FROM eclipse-temurin:25.0.5_8-jdk-noble AS development
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=extract /extract/layers/dependencies/ ./
COPY --from=extract /extract/layers/spring-boot-loader/ ./
COPY --from=extract /extract/layers/snapshot-dependencies/ ./
COPY --from=extract --chown=app:app /extract/layers/application/ ./
COPY --from=builder /out/probe/ probe/
RUN install -d -o app -g app /app/files
ENV APP_PORT=3000
EXPOSE 3000
USER 10001:10001
ENTRYPOINT ["java", "-Duser.timezone=UTC", "org.springframework.boot.loader.launch.JarLauncher"]

FROM eclipse-temurin:25.0.5_8-jre-noble AS production
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=extract /extract/layers/dependencies/ ./
COPY --from=extract /extract/layers/spring-boot-loader/ ./
COPY --from=extract /extract/layers/snapshot-dependencies/ ./
COPY --from=extract /extract/layers/application/ ./
COPY --from=builder /out/probe/ probe/
RUN install -d -o app -g app /app/files
ENV APP_PORT=3000 \
    HEALTH_MEMORY_HEAP_THRESHOLD_MB=500
EXPOSE 3000
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+UseZGC", "-Duser.timezone=UTC", "org.springframework.boot.loader.launch.JarLauncher"]

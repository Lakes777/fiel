# syntax=docker/dockerfile:1
# Duas imagens do Fiel, montadas no PC (x86) para a VM ARM:
#   docker buildx build --platform linux/arm64 --target api -t fiel:latest .
#   docker buildx build --platform linux/arm64 --target web -t fiel-web:latest .
# As etapas de compilação rodam na arquitetura de quem monta ($BUILDPLATFORM), sem emulação: o
# resultado (bytecode da JVM, JavaScript) é igual em qualquer processador. Só as finais são ARM.

# 1. A API: o jar do Spring Boot, já extraído em camadas (app + lib/), que parte mais rápido
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS compilacao-api
WORKDIR /fonte
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
# Compilador do Kotlin dentro do Gradle (sem um segundo processo) e memória contida: o PC que monta
# tem pouca RAM, dividida com o Docker
ENV GRADLE_OPTS="-Xmx1g"
# O cache do Gradle (dependências e compilador) fica entre uma montagem e outra, fora da imagem
RUN --mount=type=cache,target=/root/.gradle \
	./gradlew --no-daemon -q -Pkotlin.compiler.execution.strategy=in-process bootJar \
	&& java -Djarmode=tools -jar build/libs/fiel-0.0.1-SNAPSHOT.jar extract --destination /app

FROM eclipse-temurin:25-jre-alpine AS api
ARG COMMIT=desconhecido
LABEL org.opencontainers.image.revision=$COMMIT
RUN addgroup -S fiel && adduser -S -G fiel fiel
WORKDIR /app
COPY --from=compilacao-api /app ./
# O processo não roda como root
USER fiel
# A JVM cabe no limite do contêiner (compose: 512 MB, com no máximo 40 threads do Tomcat): heap de
# até 300 MB, e o resto com teto (metaspace, código compilado, memória direta) para um pico não
# passar do limite. Serial GC: uma VM pequena, sem núcleos sobrando para o G1.
ENV JAVA_TOOL_OPTIONS="-Xmx300m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m \
-XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=32m"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
	CMD wget -qO- http://127.0.0.1:8080/saude > /dev/null || exit 1
# As migrações rodam à parte, com o dono (ver deploy/publicar.sh):
#   docker run --rm --entrypoint java fiel -cp fiel-0.0.1-SNAPSHOT.jar io.github.lakes777.fiel.MigrarKt
ENTRYPOINT ["java", "-jar", "fiel-0.0.1-SNAPSHOT.jar"]

# 2. As telas: o build do Vite servido por um Caddy mínimo (sem HTTPS: o Caddy da VM fica na frente)
FROM --platform=$BUILDPLATFORM node:24-alpine AS compilacao-web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web ./
RUN npm run build

FROM caddy:2-alpine AS web
ARG COMMIT=desconhecido
LABEL org.opencontainers.image.revision=$COMMIT
COPY deploy/web.Caddyfile /etc/caddy/Caddyfile
COPY --from=compilacao-web /web/dist /srv

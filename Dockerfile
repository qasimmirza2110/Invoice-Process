# Actify Invoice Process - API-only backend image for a Render Web Service (docs/RENDER_DEPLOYMENT.md).
#
# Java: the code is compiled with maven.compiler.release=8 (Java 8 language and APIs) on JDK 17, exactly as on the
# developer machine, and runs on a Java 17 JRE (Spring Boot 2.7 supports Java 8-21).
# Images are pinned to exact versions (verified on Docker Hub, published 2026-09/10). Update them deliberately,
# e.g. monthly for JRE security fixes, and rebuild/re-test after each change.

# ---------- build stage ----------
# Maven 3.9.16 = the version in .mvn/wrapper/maven-wrapper.properties.
FROM maven:3.9.16-eclipse-temurin-17-noble AS build
WORKDIR /workspace

# Dependencies first, so they are cached while only the source changes.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are not run inside the image build: they run before release with ./mvnw test (and -Ppgtest against the
# isolated test database), which needs neither Docker nor network access to a database.
RUN mvn -B -q -DskipTests package \
    && cp target/actify-invoice-process-*.jar /workspace/app.jar

# ---------- runtime stage ----------
FROM eclipse-temurin:17.0.20_8-jre-noble

# Run as an unprivileged user. Nothing is stored in the container: its filesystem is wiped on every deploy, restart
# and spin-down, so the render profile keeps uploaded PDFs in the private S3-compatible bucket (INVOICE_S3_*) and
# refuses to start without it (StorageStartupCheck).
RUN groupadd --system invoice && useradd --system --gid invoice --home-dir /app --shell /usr/sbin/nologin invoice \
    && mkdir -p /app \
    && chown -R invoice:invoice /app
WORKDIR /app
COPY --from=build --chown=invoice:invoice /workspace/app.jar /app/app.jar
USER invoice

# The render profile binds 0.0.0.0:$PORT (Render default 10000). No secrets or connection values are baked in:
# everything comes from the service's environment variables at runtime.
ENV SPRING_PROFILES_ACTIVE=render \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 10000

# exec form: the JVM is PID 1 and receives SIGTERM directly, so graceful shutdown works.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

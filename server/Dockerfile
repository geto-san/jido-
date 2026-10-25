# Multi-stage build: Gradle compiles the jar, a JRE runtime image serves it.
#
# The runtime image needs ffmpeg and yt-dlp, because MediaResolver-equivalent
# resolution shells out to the real binary rather than reimplementing its
# extractors in Java.

# ---- build ------------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build

WORKDIR /workspace

# Copy the wrapper and build files first so dependency resolution is cached
# independently of source changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# ---- runtime ----------------------------------------------------------------
FROM eclipse-temurin:17-jre

# ffmpeg for merging/muxing, ca-certificates for outbound TLS to YouTube et al.
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        ca-certificates \
        curl \
    && rm -rf /var/lib/apt/lists/*

# Pin yt-dlp deliberately: it is the one dependency whose breakage is
# externally dictated, and an unpinned "latest" turns a working deploy into a
# surprise overnight. Bump the tag explicitly.
ARG YTDLP_RELEASE=2025.09.26
RUN curl -fsSL -o /usr/local/bin/yt-dlp \
        "https://github.com/yt-dlp/yt-dlp/releases/download/${YTDLP_RELEASE}/yt-dlp_linux" \
    && chmod 0755 /usr/local/bin/yt-dlp \
    && yt-dlp --version

# Non-root: a resolver that shells out to a subprocess parsing attacker-supplied
# URLs should not run as root.
RUN useradd --system --create-home --uid 10001 jido
USER 10001

WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Matches application.yml's default so a bare `docker run` works.
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
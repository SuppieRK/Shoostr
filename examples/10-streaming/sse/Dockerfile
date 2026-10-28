# Build from the repository root; no host Java or Gradle is needed.
FROM eclipse-temurin:21-jdk@sha256:5d90b26c0784ccf55af55f97cbcf7f3969c66a06299c93bdc72649219942a48f AS source
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew

FROM source AS distribution
RUN --mount=type=cache,target=/root/.gradle ./gradlew :sse-example:installDist --no-daemon --console=plain

FROM source AS jib-build
RUN --mount=type=cache,target=/root/.gradle ./gradlew :sse-example:jibBuildTar --no-daemon --console=plain

# Export with --target jib --output type=local,dest=build/sse-example-jib.
FROM scratch AS jib
COPY --from=jib-build /workspace/examples/10-streaming/sse/build/jib-image.tar /jib-image.tar

FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99 AS runtime
WORKDIR /app
COPY --from=distribution --chown=1000:1000 /workspace/examples/10-streaming/sse/build/install/sse-example/ /app/
USER 1000:1000
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["/app/bin/sse-example"]

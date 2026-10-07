# Built from the repository root: docker build -f rt-bridge/Dockerfile -t rt-bridge .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q :rt-bridge:test :rt-bridge:installDist

FROM eclipse-temurin:25-jre
COPY --from=build /src/rt-bridge/build/install/rt-bridge /app
USER nobody
# an out-of-memory JVM exits and the restart policy brings it back, instead of serving stale feeds; protobuf-java still uses sun.misc.Unsafe
ENV JAVA_OPTS="-XX:+ExitOnOutOfMemoryError --sun-misc-unsafe-memory-access=allow"
EXPOSE 8080
CMD ["/app/bin/rt-bridge"]

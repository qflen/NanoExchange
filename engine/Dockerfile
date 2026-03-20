# syntax=docker/dockerfile:1.7
# Build the network application jar (which transitively includes the engine) using the
# Gradle wrapper in a full JDK image, then copy the runtime artifacts into a slim JRE image.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY engine engine
COPY network network
COPY bench bench
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :network:installDist -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/network/build/install/network /app
EXPOSE 9000/tcp
# TCP port, multicast group, multicast port. Override via compose / CLI.
ENV NX_TCP_PORT=9000 \
    NX_MCAST_GROUP=239.200.6.42 \
    NX_MCAST_PORT=6666
ENTRYPOINT ["/bin/sh","-c","exec /app/bin/network \"$NX_TCP_PORT\" \"$NX_MCAST_GROUP\" \"$NX_MCAST_PORT\""]

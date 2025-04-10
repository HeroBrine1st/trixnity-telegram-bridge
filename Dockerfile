FROM eclipse-temurin:17 AS builder

ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.vfs.watch=false"

WORKDIR /home/gradle/build
COPY settings.gradle.kts build.gradle.kts gradle.properties gradlew ./
COPY gradle ./gradle/

COPY bridge/build.gradle.kts bridge/

# Dependencies
RUN ./gradlew :bridge:jvmMainClasses

COPY . .

RUN ./gradlew :bridge:shadowJar

FROM eclipse-temurin:17-jre AS runner
WORKDIR /app
COPY --from=builder /home/gradle/build/bridge/build/libs/bridge-*-all.jar /app/server.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh

ENTRYPOINT ["/docker-entrypoint.sh"]
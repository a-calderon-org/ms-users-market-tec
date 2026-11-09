# ms-users container image (Azure Container Apps staging, MTEC-37).
# Build stage runs on the build host's architecture; bytecode is portable.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src/ src/
# Tests run in CI (ms-users CI) for the commit being packaged; the image only packages it.
RUN ./mvnw -q -B -DskipTests package && cp target/ms-users-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build /app.jar /opt/ms-users/app.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1 -Xss512k"
ENTRYPOINT ["java", "-jar", "/opt/ms-users/app.jar", "--server.port=8080"]

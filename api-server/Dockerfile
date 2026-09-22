# 빌드 컨텍스트: 레포 루트 (proto/ 를 함께 읽는다)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY proto ./proto
COPY api-server ./api-server
WORKDIR /workspace/api-server
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/api-server/build/libs/api-server-*[!n].jar app.jar
RUN useradd --system --uid 10001 app
USER app
# 8080: REST + SSE (콘솔), 9090: gRPC (에이전트)
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

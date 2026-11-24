# 빌드 컨텍스트: 레포 루트 (proto/ 를 함께 읽는다)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY proto ./proto
COPY api-server ./api-server
WORKDIR /workspace/api-server
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/api-server/build/libs/api-server-*[^n].jar app.jar
RUN useradd --system --uid 10001 app
USER app
# 8080: REST + SSE (콘솔), 9090: gRPC (에이전트)
EXPOSE 8080 9090
# 다이렉트 메모리 상한. 기본값은 최대 힙과 같아(운영 약 950MB) 동시 업로드가 몰리면 힙 밖에서 그만큼 더 커질 수 있다.
# gRPC 수신 창 256KiB 기준 300건 동시 업로드 최고치가 약 130MiB라 두 배 여유로 둔다. 넘으면 그 요청만 실패한다(fail-close).
ENTRYPOINT ["java", "-XX:MaxDirectMemorySize=256m", "-jar", "/app/app.jar"]

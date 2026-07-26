FROM eclipse-temurin:21-jdk-jammy
RUN groupadd -g 1000 synapse_mcp && useradd -u 1000 -g synapse_mcp -s /usr/sbin/nologin -M synapse_mcp
WORKDIR /app
COPY --chmod=755 mvnw pom.xml ./
COPY .mvn/ .mvn/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B clean package -DskipTests -Dspotless.check.skip=true && mv target/*.jar application.jar && rm -rf target src && mkdir -p lucene-index && chown -R synapse_mcp:synapse_mcp lucene-index application.jar
USER synapse_mcp
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError" SYNAPSEMCP_LUCENE_BASE_DIR="/app/lucene-index"
EXPOSE 8080
EXPOSE 5432
EXPOSE 6379
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3 && timeout 3 cat <&3 | grep -q "\"status\":\"UP\""' || exit 1
ENTRYPOINT ["java", "-jar", "application.jar"]
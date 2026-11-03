# Build the JAR first with scripts/Start-DockerDesktop.ps1 (host Oracle JDK 21).
FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
RUN addgroup -g 10001 app && adduser -D -u 10001 -G app app \
    && mkdir -p /app /var/lib/interview-coach \
    && chown app:app /app /var/lib/interview-coach \
    && chmod 700 /var/lib/interview-coach
WORKDIR /app
COPY --chown=app:app target/interview-coach-backend-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-Djdk.httpclient.redirects.retrylimit=1", "-Djdk.httpclient.disableRetryConnect=true", "-Djdk.httpclient.enableAllMethodRetry=false", "-jar", "/app/app.jar"]

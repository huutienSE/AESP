FROM maven:3.9.16-eclipse-temurin-26-alpine AS build
WORKDIR /build
COPY pom.xml .
COPY src/main/java/ src/main/java/
COPY src/main/resources/application.properties src/main/resources/application.properties
RUN mvn -B -DskipTests package

FROM eclipse-temurin:26.0.2.1_1-jre-alpine
ARG AESP_AI_RELEASE=local
LABEL org.opencontainers.image.version="${AESP_AI_RELEASE}"
RUN apk add --no-cache curl && addgroup -S aesp && adduser -S aesp -G aesp
WORKDIR /app
COPY --from=build --chown=aesp:aesp /build/target/ai-evaluation-service-0.0.1-SNAPSHOT.jar app.jar
USER aesp
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

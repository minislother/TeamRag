# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN addgroup --system teamrag && adduser --system --ingroup teamrag teamrag

COPY --from=build /workspace/target/team-rag-knowledge-base-1.0.0.jar /app/app.jar

ENV JAVA_OPTS=""
EXPOSE 8080

USER teamrag
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

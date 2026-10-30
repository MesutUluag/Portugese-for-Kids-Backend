FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B

COPY src ./src
RUN ./mvnw package -DskipTests -B

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/target/portugese-for-kids-backend-0.0.1-SNAPSHOT.jar app.jar

RUN chown 1001:0 /app/app.jar

USER 1001

ENV SERVER_ADDRESS=0.0.0.0 \
    PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

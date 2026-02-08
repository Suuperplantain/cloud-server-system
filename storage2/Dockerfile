FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY . .
RUN mvn -q -DskipTests -pl storage2 -am package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/storage2/target/storage2-1.0-SNAPSHOT.jar /app/app.jar
EXPOSE 9102
CMD ["java","-jar","/app/app.jar"]

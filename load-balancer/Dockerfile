FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY . .
RUN mvn -q -DskipTests -pl load-balancer -am package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/load-balancer/target/load-balancer-1.0-SNAPSHOT-all.jar /app/app.jar
EXPOSE 9000
CMD ["java","-jar","/app/app.jar"]
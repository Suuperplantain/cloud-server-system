FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/load-balancer-1.0-SNAPSHOT.jar /app/app.jar
EXPOSE 9000
CMD ["java","-jar","/app/app.jar"]

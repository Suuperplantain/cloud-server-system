FROM openjdk:17-slim

WORKDIR /app

COPY src/main/java /app

RUN javac Servers.java ClientHandler.java

EXPOSE 9101

CMD ["java", "Servers"]

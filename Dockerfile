FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY src/main/java src
COPY cluster.docker.properties .
RUN javac -d classes $(find src -name '*.java')
ENTRYPOINT ["java", "-cp", "classes", "com.novadb.NovaDBApplication"]

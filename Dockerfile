FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/*.jar app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:ActiveProcessorCount=2"
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]

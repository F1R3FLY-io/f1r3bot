# use the base image from the JDK
FROM openjdk:17-jdk-slim

# specify the working directory
WORKDIR /app

# copy the assembled "fat JAR" file to the working directory
COPY build/libs/F1r3flyBots-1.0-SNAPSHOT-all.jar /app/DiscordBot.jar

# copy the configuration files
COPY src/main/resources/application.properties /app/config/application.properties
COPY src/main/resources/logback.xml /app/config/logback.xml

# specify the command to launch the application with logging configured through logback.xml
CMD ["java", "-Dlogging.config=/app/config/logback.xml", "-jar", "DiscordBot.jar"]

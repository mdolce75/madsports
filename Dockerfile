# Mad Sports (RinkReports + RinkMetrics) en un contenedor: se compila con Maven y corre con el JRE 21.
# Lo usa deploy/docker-compose.prod.yml (con PostgreSQL y Caddy); ver docs/despliegue-donweb.md.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# mvnw con fin de línea de Windows (si se construye desde un checkout de Windows) no corre en Linux.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:21-jre
ENV TZ=America/Argentina/Buenos_Aires \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
RUN useradd --system --create-home madsports && mkdir -p /data/informes && chown -R madsports /data
WORKDIR /app
COPY --from=build /src/app/target/madsports-app-0.1.0-SNAPSHOT.jar app.jar
USER madsports
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

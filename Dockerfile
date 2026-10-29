FROM eclipse-temurin:11-jdk-jammy AS build
WORKDIR /source
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon --max-workers=2 \
    -PgradleGitPropertiesVersion=2.4.2 :tennis-stats:distTar :data-load:installDist \
    && mkdir /distribution \
    && tar -xf tennis-stats/build/distributions/tennis-stats-*.tar -C /distribution --strip-components=1

FROM eclipse-temurin:11-jre-jammy
WORKDIR /app
COPY --from=build /distribution/lib/ ./lib/
COPY --from=build /source/data-load/build/install/data-load/ ./data-load/
COPY --from=build /source/scripts/ ./scripts/
ENV SPRING_PROFILES_ACTIVE=railway
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-cp", "/app/lib/*", "org.strangeforest.tcb.stats.TennisStatsApplication"]

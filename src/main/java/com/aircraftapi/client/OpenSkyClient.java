package com.aircraftapi.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Component
public class OpenSkyClient {

    private static final Logger log = LoggerFactory.getLogger(OpenSkyClient.class);

    private final WebClient webClient;
    private final int timeoutSeconds;

    public OpenSkyClient(
            @Value("${opensky.base-url}") String baseUrl,
            @Value("${opensky.timeout-seconds}") int timeoutSeconds) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.timeoutSeconds = timeoutSeconds;
    }

    public List<List<Object>> fetchAircraftInBoundingBox(
            double minLat, double maxLat, double minLon, double maxLon) {

        log.info("Calling OpenSky API — bbox: lat[{},{}] lon[{},{}]", minLat, maxLat, minLon, maxLon);

        try {
            OpenSkyResponse response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/states/all")
                            .queryParam("lamin", minLat)
                            .queryParam("lomin", minLon)
                            .queryParam("lamax", maxLat)
                            .queryParam("lomax", maxLon)
                            .build())
                    .retrieve()
                    .bodyToMono(OpenSkyResponse.class)
                    .block(Duration.ofSeconds(timeoutSeconds));

            if (response == null || response.getStates() == null) {
                log.warn("OpenSky returned empty response");
                return Collections.emptyList();
            }

            log.info("OpenSky returned {} aircraft", response.getStates().size());
            return response.getStates();

        } catch (Exception e) {
            log.error("OpenSky API call failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}

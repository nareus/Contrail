package com.aircraftapi.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class OpenSkyClient {

    private static final Logger log = LoggerFactory.getLogger(OpenSkyClient.class);

    private final WebClient webClient;
    private final WebClient tokenClient;
    private final int timeoutSeconds;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;

    private String accessToken;
    private Instant tokenExpiry = Instant.MIN;

    public OpenSkyClient(
            @Value("${opensky.base-url}") String baseUrl,
            @Value("${opensky.token-url}") String tokenUrl,
            @Value("${opensky.client-id:}") String clientId,
            @Value("${opensky.client-secret:}") String clientSecret,
            @Value("${opensky.timeout-seconds}") int timeoutSeconds) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.tokenClient = WebClient.builder().build();
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeoutSeconds = timeoutSeconds;
    }

    public List<List<Object>> fetchAircraftInBoundingBox(
            double minLat, double maxLat, double minLon, double maxLon) {

        log.info("Calling OpenSky API — bbox: lat[{},{}] lon[{},{}]", minLat, maxLat, minLon, maxLon);

        try {
            var requestSpec = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/states/all")
                            .queryParam("lamin", minLat)
                            .queryParam("lomin", minLon)
                            .queryParam("lamax", maxLat)
                            .queryParam("lomax", maxLon)
                            .build());

            if (isAuthConfigured()) {
                String token = getAccessToken();
                requestSpec = requestSpec.header("Authorization", "Bearer " + token);
            }

            OpenSkyResponse response = requestSpec
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

    private boolean isAuthConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    @SuppressWarnings("unchecked")
    private synchronized String getAccessToken() {
        if (accessToken != null && Instant.now().isBefore(tokenExpiry)) {
            return accessToken;
        }

        log.info("Fetching new OpenSky OAuth2 token");

        Map<String, Object> tokenResponse = tokenClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret))
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(10));

        if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
            throw new RuntimeException("Failed to obtain OpenSky access token");
        }

        accessToken = (String) tokenResponse.get("access_token");
        int expiresIn = (int) tokenResponse.get("expires_in");
        tokenExpiry = Instant.now().plusSeconds(expiresIn - 60); // refresh 1 min early

        log.info("OpenSky token obtained, expires in {}s", expiresIn);
        return accessToken;
    }
}

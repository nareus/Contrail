package com.aircraftapi.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "aircraft_snapshots")
public class AircraftSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "icao24", nullable = false, length = 10)
    private String icao24;

    @Column(name = "callsign", length = 20)
    private String callsign;

    @Column(name = "origin_country", length = 100)
    private String originCountry;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "altitude_meters")
    private Double altitudeMeters;

    @Column(name = "velocity_ms")
    private Double velocityMs;

    @Column(name = "heading")
    private Double heading;

    @Column(name = "on_ground", nullable = false)
    private boolean onGround;

    @CreationTimestamp
    @Column(name = "captured_at", nullable = false, updatable = false)
    private OffsetDateTime capturedAt;

    public AircraftSnapshot() {}

    public AircraftSnapshot(String icao24, String callsign, String originCountry,
                            Double latitude, Double longitude, Double altitudeMeters,
                            Double velocityMs, Double heading, boolean onGround) {
        this.icao24 = icao24;
        this.callsign = callsign;
        this.originCountry = originCountry;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.velocityMs = velocityMs;
        this.heading = heading;
        this.onGround = onGround;
    }

    public Long getId() { return id; }
    public String getIcao24() { return icao24; }
    public void setIcao24(String icao24) { this.icao24 = icao24; }
    public String getCallsign() { return callsign; }
    public void setCallsign(String callsign) { this.callsign = callsign; }
    public String getOriginCountry() { return originCountry; }
    public void setOriginCountry(String originCountry) { this.originCountry = originCountry; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Double getAltitudeMeters() { return altitudeMeters; }
    public void setAltitudeMeters(Double altitudeMeters) { this.altitudeMeters = altitudeMeters; }
    public Double getVelocityMs() { return velocityMs; }
    public void setVelocityMs(Double velocityMs) { this.velocityMs = velocityMs; }
    public Double getHeading() { return heading; }
    public void setHeading(Double heading) { this.heading = heading; }
    public boolean isOnGround() { return onGround; }
    public void setOnGround(boolean onGround) { this.onGround = onGround; }
    public OffsetDateTime getCapturedAt() { return capturedAt; }
}

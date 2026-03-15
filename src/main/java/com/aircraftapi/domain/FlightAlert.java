package com.aircraftapi.domain;

import com.aircraftapi.dto.AlertMessage;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "flight_alerts")
public class FlightAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String icao24;

    @Column(length = 20)
    private String callsign;

    @Column(name = "alert_type", nullable = false, length = 50)
    private String alertType;

    private Double latitude;
    private Double longitude;
    private Double altitudeMeters;

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(name = "detected_at", nullable = false, updatable = false)
    private OffsetDateTime detectedAt;

    public FlightAlert() {}

    public static FlightAlert from(AlertMessage msg) {
        FlightAlert alert = new FlightAlert();
        alert.icao24 = msg.icao24();
        alert.callsign = msg.callsign();
        alert.alertType = msg.type();
        alert.latitude = msg.latitude();
        alert.longitude = msg.longitude();
        alert.altitudeMeters = msg.altitudeMeters();
        alert.description = msg.description();
        return alert;
    }

    public Long getId() { return id; }
    public String getIcao24() { return icao24; }
    public String getCallsign() { return callsign; }
    public String getAlertType() { return alertType; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Double getAltitudeMeters() { return altitudeMeters; }
    public String getDescription() { return description; }
    public OffsetDateTime getDetectedAt() { return detectedAt; }
}

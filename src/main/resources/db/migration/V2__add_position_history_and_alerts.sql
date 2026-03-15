CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE position_history (
    id              BIGSERIAL       PRIMARY KEY,
    icao24          VARCHAR(10)     NOT NULL,
    callsign        VARCHAR(20),
    latitude        DOUBLE PRECISION NOT NULL,
    longitude       DOUBLE PRECISION NOT NULL,
    geom            GEOMETRY(Point, 4326)
                        GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)) STORED,
    altitude_meters DOUBLE PRECISION,
    velocity_ms     DOUBLE PRECISION,
    heading         DOUBLE PRECISION,
    vertical_rate   DOUBLE PRECISION,
    on_ground       BOOLEAN         NOT NULL DEFAULT false,
    captured_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pos_hist_icao24      ON position_history (icao24);
CREATE INDEX idx_pos_hist_captured_at ON position_history (captured_at DESC);
CREATE INDEX idx_pos_hist_geom        ON position_history USING GIST (geom);

CREATE TABLE flight_alerts (
    id              BIGSERIAL       PRIMARY KEY,
    icao24          VARCHAR(10)     NOT NULL,
    callsign        VARCHAR(20),
    alert_type      VARCHAR(50)     NOT NULL,
    latitude        DOUBLE PRECISION,
    longitude       DOUBLE PRECISION,
    altitude_meters DOUBLE PRECISION,
    description     TEXT,
    detected_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alerts_icao24      ON flight_alerts (icao24);
CREATE INDEX idx_alerts_detected_at ON flight_alerts (detected_at DESC);
CREATE INDEX idx_alerts_type        ON flight_alerts (alert_type);

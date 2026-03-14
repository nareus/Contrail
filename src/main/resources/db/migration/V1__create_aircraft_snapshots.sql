CREATE TABLE aircraft_snapshots (
    id               BIGSERIAL    PRIMARY KEY,
    icao24           VARCHAR(10)  NOT NULL,
    callsign         VARCHAR(20),
    origin_country   VARCHAR(100),
    latitude         DOUBLE PRECISION,
    longitude        DOUBLE PRECISION,
    altitude_meters  DOUBLE PRECISION,
    velocity_ms      DOUBLE PRECISION,
    heading          DOUBLE PRECISION,
    on_ground        BOOLEAN      NOT NULL DEFAULT false,
    captured_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_snapshots_captured_at
    ON aircraft_snapshots (captured_at DESC);

CREATE INDEX idx_snapshots_icao24
    ON aircraft_snapshots (icao24);

CREATE INDEX idx_snapshots_position
    ON aircraft_snapshots (latitude, longitude)
    WHERE latitude IS NOT NULL AND longitude IS NOT NULL;

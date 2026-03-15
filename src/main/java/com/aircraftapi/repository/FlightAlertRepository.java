package com.aircraftapi.repository;

import com.aircraftapi.domain.FlightAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FlightAlertRepository extends JpaRepository<FlightAlert, Long> {
    List<FlightAlert> findTop100ByOrderByDetectedAtDesc();
    List<FlightAlert> findByIcao24OrderByDetectedAtDesc(String icao24);
}

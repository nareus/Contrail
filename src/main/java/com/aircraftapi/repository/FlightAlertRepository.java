package com.aircraftapi.repository;

import com.aircraftapi.domain.FlightAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface FlightAlertRepository extends JpaRepository<FlightAlert, Long> {

    List<FlightAlert> findTop100ByOrderByDetectedAtDesc();

    List<FlightAlert> findByIcao24OrderByDetectedAtDesc(String icao24);

    long countByDetectedAtAfter(OffsetDateTime since);

    @Query("SELECT a FROM FlightAlert a WHERE " +
           "(:type IS NULL OR a.alertType = :type) AND " +
           "(:since IS NULL OR a.detectedAt >= :since) " +
           "ORDER BY a.detectedAt DESC")
    List<FlightAlert> findFiltered(@Param("type") String type,
                                   @Param("since") OffsetDateTime since);
}

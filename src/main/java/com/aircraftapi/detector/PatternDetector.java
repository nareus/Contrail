package com.aircraftapi.detector;

import com.aircraftapi.dto.AlertMessage;
import com.aircraftapi.dto.PositionUpdate;

import java.util.List;
import java.util.Optional;

public interface PatternDetector {
    Optional<AlertMessage> detect(PositionUpdate current, List<PositionUpdate> history);
}

package com.aircraftapi.service;

import com.aircraftapi.dto.AlertMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class AlertBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(AlertBroadcaster.class);

    private final SimpMessagingTemplate messaging;

    public AlertBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void broadcast(AlertMessage alert) {
        messaging.convertAndSend("/topic/alerts", alert);
        messaging.convertAndSend("/topic/alerts/" + alert.icao24(), alert);
        log.info("Alert broadcast: type={} icao24={} msg=\"{}\"",
                alert.type(), alert.icao24(), alert.description());
    }
}

package com.aircraftapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AircraftApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AircraftApiApplication.class, args);
    }
}

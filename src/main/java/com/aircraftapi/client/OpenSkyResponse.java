package com.aircraftapi.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenSkyResponse {

    private long time;
    private List<List<Object>> states;

    public long getTime() { return time; }
    public void setTime(long time) { this.time = time; }

    public List<List<Object>> getStates() { return states; }
    public void setStates(List<List<Object>> states) { this.states = states; }
}

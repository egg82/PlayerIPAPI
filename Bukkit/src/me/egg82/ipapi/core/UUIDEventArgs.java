package me.egg82.ipapi.core;

import java.util.Set;

import ninja.egg82.patterns.events.EventArgs;

public class UUIDEventArgs extends EventArgs {
    // vars
    private String ip = null;
    private Set<UUIDData> uuidData = null;

    // constructor
    public UUIDEventArgs(String ip, Set<UUIDData> uuidData) {
        this.ip = ip;
        this.uuidData = uuidData;
    }

    // public
    public String getIp() {
        return ip;
    }

    public Set<UUIDData> getUuidData() {
        return uuidData;
    }

    // private

}

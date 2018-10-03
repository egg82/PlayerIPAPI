package me.egg82.ipapi.core;

import java.util.Set;
import java.util.UUID;

import ninja.egg82.patterns.events.EventArgs;

public class IPEventArgs extends EventArgs {
    // vars
    private UUID uuid = null;
    private Set<IPData> ipData = null;

    // constructor
    public IPEventArgs(UUID uuid, Set<IPData> ipData) {
        this.uuid = uuid;
        this.ipData = ipData;
    }

    // public
    public UUID getUuid() {
        return uuid;
    }

    public Set<IPData> getIpData() {
        return ipData;
    }

    // private

}

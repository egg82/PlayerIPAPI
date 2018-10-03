package me.egg82.ipapi.core;

import java.util.Set;
import java.util.UUID;

import ninja.egg82.patterns.events.EventArgs;

public class IPResultEventArgs extends EventArgs {
    // vars
    public static IPResultEventArgs EMPTY = new IPResultEventArgs(null, null);

    private UUID uuid = null;
    private Set<IPData> value = null;

    // constructor
    public IPResultEventArgs(UUID uuid, Set<IPData> value) {
        this.uuid = uuid;
        this.value = value;
    }

    // public
    public UUID getUuid() {
        return uuid;
    }
    public Set<IPData> getResults() {
        return value;
    }

    // private

}

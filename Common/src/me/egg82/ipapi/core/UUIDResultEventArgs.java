package me.egg82.ipapi.core;

import java.util.Set;

import ninja.egg82.patterns.events.EventArgs;

public class UUIDResultEventArgs extends EventArgs {
    // vars
    public static UUIDResultEventArgs EMPTY = new UUIDResultEventArgs(null, null);

    private String ip = null;
    private Set<UUIDData> value = null;

    // constructor
    public UUIDResultEventArgs(String ip, Set<UUIDData> value) {
        this.ip = ip;
        this.value = value;
    }

    // public
    public String getIp() {
        return ip;
    }
    public Set<UUIDData> getResults() {
        return value;
    }

    // private

}

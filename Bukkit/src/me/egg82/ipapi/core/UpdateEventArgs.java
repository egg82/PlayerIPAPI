package me.egg82.ipapi.core;

import java.util.UUID;

import ninja.egg82.patterns.events.EventArgs;

public class UpdateEventArgs extends EventArgs {
    // vars
    public static UpdateEventArgs EMPTY = new UpdateEventArgs(null, null, -1L, -1L);

    private UUID uuid = null;
    private String ip = null;
    private long created = -1L;
    private long updated = -1L;

    // constructor
    public UpdateEventArgs(UUID uuid, String ip, long created, long updated) {
        this.uuid = uuid;
        this.ip = ip;
        this.created = created;
        this.updated = updated;
    }

    // public
    public UUID getUuid() {
        return uuid;
    }

    public String getIp() {
        return ip;
    }

    public long getCreated() {
        return created;
    }

    public long getUpdated() {
        return updated;
    }

    // private

}

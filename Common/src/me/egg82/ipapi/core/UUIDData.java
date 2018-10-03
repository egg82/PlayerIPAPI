package me.egg82.ipapi.core;

import java.util.UUID;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class UUIDData {
    // vars
    private UUID uuid = null;
    private long created = -1L;
    private long updated = -1L;

    // constructor
    public UUIDData(UUID uuid, long created, long updated) {
        this.uuid = uuid;
        this.created = created;
        this.updated = updated;
    }

    // public
    public UUID getUuid() {
        return uuid;
    }

    public long getCreated() {
        return created;
    }

    public long getUpdated() {
        return updated;
    }

    public int hashCode() {
        // I am well aware that this likely violates the hashCode agreement, but is
        // needed for fast (and accurate) imports
        return new HashCodeBuilder().append(uuid).toHashCode();
    }

    public boolean equals(Object other) {
        if (other == null) {
            return false;
        }
        if (!(other instanceof UUIDData)) {
            return false;
        }

        UUIDData o = (UUIDData) other;
        // I am well aware that this likely violates the equals agreement, but is needed
        // for fast (and accurate) imports
        return new EqualsBuilder().append(uuid, o.uuid).isEquals();
    }

    // private

}

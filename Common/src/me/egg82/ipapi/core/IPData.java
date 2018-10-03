package me.egg82.ipapi.core;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class IPData {
    // vars
    private String ip = null;
    private long created = -1L;
    private long updated = -1L;

    // constructor
    public IPData(String ip, long created, long updated) {
        this.ip = ip;
        this.created = created;
        this.updated = updated;
    }

    // public
    public String getIp() {
        return ip;
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
        return new HashCodeBuilder().append(ip).toHashCode();
    }

    public boolean equals(Object other) {
        if (other == null) {
            return false;
        }
        if (!(other instanceof IPData)) {
            return false;
        }

        IPData o = (IPData) other;
        // I am well aware that this likely violates the equals agreement, but is needed
        // for fast (and accurate) imports
        return new EqualsBuilder().append(ip, o.ip).isEquals();
    }

    // private

}

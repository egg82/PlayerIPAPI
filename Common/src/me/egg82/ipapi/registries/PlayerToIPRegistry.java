package me.egg82.ipapi.registries;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import me.egg82.ipapi.Configuration;
import me.egg82.ipapi.core.IPData;
import ninja.egg82.enums.ExpirationPolicy;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.ExpiringRegistry;
import ninja.egg82.utils.TimeUtil;

public class PlayerToIPRegistry extends ExpiringRegistry<UUID, Set<IPData>> {
    // vars

    // constructor
    @SuppressWarnings("unchecked")
    public PlayerToIPRegistry() {
        super(new UUID[0], new Set[0], TimeUtil.getTime(ServiceLocator.getService(Configuration.class).getNode("cacheTime").getString("1minute")), TimeUnit.MILLISECONDS, ExpirationPolicy.ACCESSED);
    }

    // public

    // private

}

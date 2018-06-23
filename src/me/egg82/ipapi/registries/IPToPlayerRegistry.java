package me.egg82.ipapi.registries;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import me.egg82.ipapi.core.UUIDData;
import ninja.egg82.bukkit.services.ConfigRegistry;
import ninja.egg82.enums.ExpirationPolicy;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.ExpiringRegistry;
import ninja.egg82.utils.TimeUtil;

public class IPToPlayerRegistry extends ExpiringRegistry<String, Set<UUIDData>> {
	//vars
	
	//constructor
	@SuppressWarnings("unchecked")
	public IPToPlayerRegistry() {
		super(new String[0], new Set[0], TimeUtil.getTime(ServiceLocator.getService(ConfigRegistry.class).getRegister("cacheTime", String.class)), TimeUnit.MILLISECONDS, ExpirationPolicy.ACCESSED);
	}
	
	//public
	
	//private
	
}

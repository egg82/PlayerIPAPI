package me.egg82.ipapi.registries;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import ninja.egg82.bukkit.services.ConfigRegistry;
import ninja.egg82.enums.ExpirationPolicy;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.ExpiringRegistry;
import ninja.egg82.utils.TimeUtil;

public class PlayerToIPRegistry extends ExpiringRegistry<UUID, Set<String>> {
	//vars
	
	//constructor
	@SuppressWarnings("unchecked")
	public PlayerToIPRegistry() {
		super(new UUID[0], new Set[0], TimeUtil.getTime(ServiceLocator.getService(ConfigRegistry.class).getRegister("cacheTime", String.class)), TimeUnit.MILLISECONDS, ExpirationPolicy.ACCESSED);
	}
	
	//public
	
	//private
	
}

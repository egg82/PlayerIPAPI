package me.egg82.ipapi.core;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import me.egg82.ipapi.registries.IPToPlayerRegistry;
import me.egg82.ipapi.registries.PlayerToIPRegistry;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IRegistry;
import redis.clients.jedis.JedisPubSub;

public class RedisSubscriber extends JedisPubSub {
	//vars
	
	//constructor
	public RedisSubscriber() {
		super();
	}
	
	//public
	public void onMessage(String channel, String message) {
		if (message == null || message.isEmpty()) {
			return;
		}
		if (!channel.equals("pipapi")) {
			return;
		}
		
		String[] parts = message.split(",\\s?");
		if (parts.length != 2) {
			return;
		}
		
		UUID uuid = UUID.fromString(parts[0]);
		String ip = parts[1];
		
		IRegistry<UUID, Set<String>> playerToIpRegistry = ServiceLocator.getService(PlayerToIPRegistry.class);
		IRegistry<String, Set<UUID>> ipToPlayerRegistry = ServiceLocator.getService(IPToPlayerRegistry.class);
		
		Set<String> ips = playerToIpRegistry.getRegister(uuid);
		if (ips == null) {
			ips = new HashSet<String>();
			playerToIpRegistry.setRegister(uuid, ips);
		}
		ips.add(ip);
		
		Set<UUID> uuids = ipToPlayerRegistry.getRegister(ip);
		if (uuids == null) {
			uuids = new HashSet<UUID>();
			ipToPlayerRegistry.setRegister(ip, uuids);
		}
		uuids.add(uuid);
	}
	
	//private
	
}

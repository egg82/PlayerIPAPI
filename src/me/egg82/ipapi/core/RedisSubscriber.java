package me.egg82.ipapi.core;

import java.util.UUID;

import me.egg82.ipapi.utils.PlayerCacheUtil;
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
		
		PlayerCacheUtil.addIp(uuid, ip, false);
		PlayerCacheUtil.addUuid(ip, uuid, false);
	}
	
	//private
	
}

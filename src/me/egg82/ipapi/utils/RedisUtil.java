package me.egg82.ipapi.utils;

import ninja.egg82.bukkit.services.ConfigRegistry;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IVariableRegistry;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class RedisUtil {
	//vars
	
	//constructor
	public RedisUtil() {
		
	}
	
	//public
	@SuppressWarnings("resource")
	public static Jedis getRedis() {
		Jedis redis = null;
		
		IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
		JedisPool redisPool = ServiceLocator.getService(JedisPool.class);
		if (redisPool != null) {
			redis = redisPool.getResource();
			if (configRegistry.hasRegister("redis.pass")) {
				redis.auth(configRegistry.getRegister("redis.pass", String.class));
			}
		}
		
		return redis;
	}
	
	//private
	
}

package me.egg82.ipapi;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import me.egg82.ipapi.core.IPEventArgs;
import me.egg82.ipapi.core.UUIDEventArgs;
import me.egg82.ipapi.registries.IPToPlayerRegistry;
import me.egg82.ipapi.registries.PlayerToIPRegistry;
import me.egg82.ipapi.sql.SelectIpsCommand;
import me.egg82.ipapi.sql.SelectUuidsCommand;
import ninja.egg82.bukkit.services.ConfigRegistry;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IExpiringRegistry;
import ninja.egg82.patterns.registries.IVariableRegistry;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class IPLookupAPI {
	//vars
	
	//constructor
	public IPLookupAPI() {
		
	}
	
	//public
	public static IPLookupAPI getInstance() {
		return PlayerIPAPI.getAPI();
	}
	
	public Set<String> getIps(UUID playerUuid) {
		return getIps(playerUuid, true);
	}
	public Set<String> getIps(UUID playerUuid, boolean expensive) {
		if (playerUuid == null) {
			throw new IllegalArgumentException("playerUuid cannot be null.");
		}
		
		// Internal cache - use first
		IExpiringRegistry<UUID, Set<String>> playerToIpRegistry = ServiceLocator.getService(PlayerToIPRegistry.class);
		Set<String> ips = playerToIpRegistry.getRegister(playerUuid);
		if (ips != null) {
			return ips;
		}
		
		// Redis - use INSTEAD of SQL
		JedisPool redisPool = ServiceLocator.getService(JedisPool.class);
		if (redisPool != null) {
			// Grab new Jedis instance to push updates
			IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
			Jedis redis = redisPool.getResource();
			if (configRegistry.hasRegister("redis.pass")) {
				redis.auth(configRegistry.getRegister("redis.pass", String.class));
			}
			
			String key = "pipapi:uuid:" + playerUuid.toString();
			Set<String> list = redis.smembers(key);
			ips = (list.size() > 0) ? new HashSet<String>(list) : new HashSet<String>();
			
			// Cleanup Jedis
			redis.close();
			
			// Cache the result
			playerToIpRegistry.setRegister(playerUuid, ips);
			return ips;
		}
		
		if (!expensive) {
			// Non-expensive call. Return nothing, but don't cache this result
			return new HashSet<String>();
		}
		
		// SQL - use as a last resort
		AtomicReference<Set<String>> retVal = new AtomicReference<Set<String>>(null);
		CountDownLatch latch = new CountDownLatch(1);
		
		BiConsumer<Object, IPEventArgs> sqlData = (s, e) -> {
			retVal.set(e.getIps());
			latch.countDown();
		};
		
		SelectIpsCommand command = new SelectIpsCommand(playerUuid);
		command.onData().attach(sqlData);
		command.start();
		
		try {
			latch.await();
		} catch (Exception ex) {
			ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
		}
		
		command.onData().detatch(sqlData);
		
		if (retVal.get() == null) {
			// Something went wrong. Don't cache this
			return new HashSet<String>();
		}
		
		ips = retVal.get();
		// Cache the result
		playerToIpRegistry.setRegister(playerUuid, ips);
		
		return ips;
	}
	public Set<UUID> getPlayers(String ip) {
		return getPlayers(ip, true);
	}
	public Set<UUID> getPlayers(String ip, boolean expensive) {
		if (ip == null) {
			throw new IllegalArgumentException("ip cannot be null.");
		}
		
		// Internal cache - use first
		IExpiringRegistry<String, Set<UUID>> ipToPlayerRegistry = ServiceLocator.getService(IPToPlayerRegistry.class);
		Set<UUID> uuids = ipToPlayerRegistry.getRegister(ip);
		if (uuids != null) {
			return uuids;
		}
		
		// Redis - use INSTEAD of SQL
		JedisPool redisPool = ServiceLocator.getService(JedisPool.class);
		if (redisPool != null) {
			// Grab new Jedis instance to push updates
			IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
			Jedis redis = redisPool.getResource();
			if (configRegistry.hasRegister("redis.pass")) {
				redis.auth(configRegistry.getRegister("redis.pass", String.class));
			}
			
			String key = "pipapi:ip:" + ip;
			Set<String> list = redis.smembers(key);
			if (list.size() > 0) {
				uuids = new HashSet<UUID>();
				for (String uuid : list) {
					uuids.add(UUID.fromString(uuid));
				}
			} else {
				uuids = new HashSet<UUID>();
			}
			
			// Cleanup Jedis
			redis.close();
			
			// Cache the result
			ipToPlayerRegistry.setRegister(ip, uuids);
			return uuids;
		}
		
		if (!expensive) {
			// Non-expensive call. Return nothing, but don't cache this result
			return new HashSet<UUID>();
		}
		
		// SQL - use as a last resort
		AtomicReference<Set<UUID>> retVal = new AtomicReference<Set<UUID>>(null);
		CountDownLatch latch = new CountDownLatch(1);
		
		BiConsumer<Object, UUIDEventArgs> sqlData = (s, e) -> {
			retVal.set(e.getUuids());
			latch.countDown();
		};
		
		SelectUuidsCommand command = new SelectUuidsCommand(ip);
		command.onData().attach(sqlData);
		command.start();
		
		try {
			latch.await();
		} catch (Exception ex) {
			ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
		}
		
		command.onData().detatch(sqlData);
		
		if (retVal.get() == null) {
			// Something went wrong. Don't cache this
			return new HashSet<UUID>();
		}
		
		uuids = retVal.get();
		// Cache the result
		ipToPlayerRegistry.setRegister(ip, uuids);
		
		return uuids;
	}
	
	//private
	
}

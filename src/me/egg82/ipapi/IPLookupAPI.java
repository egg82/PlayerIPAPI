package me.egg82.ipapi;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import com.google.common.collect.ImmutableSet;

import me.egg82.ipapi.core.IPEventArgs;
import me.egg82.ipapi.core.UUIDEventArgs;
import me.egg82.ipapi.registries.IPToPlayerRegistry;
import me.egg82.ipapi.registries.PlayerToIPRegistry;
import me.egg82.ipapi.sql.SelectIpsCommand;
import me.egg82.ipapi.sql.SelectUuidsCommand;
import me.egg82.ipapi.utils.RedisUtil;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IExpiringRegistry;
import redis.clients.jedis.Jedis;

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
			return ImmutableSet.copyOf(ips);
		}
		
		// Redis - use INSTEAD of SQL
		try (Jedis redis = RedisUtil.getRedis()) {
			if (redis != null) {
				String key = "pipapi:uuid:" + playerUuid.toString();
				Set<String> list = redis.smembers(key);
				ips = (list.size() > 0) ? new HashSet<String>(list) : new HashSet<String>();
				
				// Cache the result
				playerToIpRegistry.setRegister(playerUuid, ips);
				return ImmutableSet.copyOf(ips);
			}
		}
		
		if (!expensive) {
			// Non-expensive call. Return nothing, but don't cache this result
			return ImmutableSet.of();
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
			return ImmutableSet.of();
		}
		
		ips = retVal.get();
		// Cache the result
		playerToIpRegistry.setRegister(playerUuid, ips);
		
		return ImmutableSet.copyOf(ips);
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
			return ImmutableSet.copyOf(uuids);
		}
		
		// Redis - use INSTEAD of SQL
		try (Jedis redis = RedisUtil.getRedis()) {
			if (redis != null) {
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
				
				// Cache the result
				ipToPlayerRegistry.setRegister(ip, uuids);
				return ImmutableSet.copyOf(uuids);
			}
		}
		
		if (!expensive) {
			// Non-expensive call. Return nothing, but don't cache this result
			return ImmutableSet.of();
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
			return ImmutableSet.of();
		}
		
		uuids = retVal.get();
		// Cache the result
		ipToPlayerRegistry.setRegister(ip, uuids);
		
		return ImmutableSet.copyOf(uuids);
	}
	
	//private
	
}

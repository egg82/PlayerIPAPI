package me.egg82.ipapi;

import java.util.HashSet;
import java.util.List;
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
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IRegistry;
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
	@SuppressWarnings("resource")
	public Set<String> getIps(UUID playerUuid, boolean expensive) {
		if (playerUuid == null) {
			throw new IllegalArgumentException("playerUuid cannot be null.");
		}
		
		// Internal cache
		IRegistry<UUID, Set<String>> playerToIpRegistry = ServiceLocator.getService(PlayerToIPRegistry.class);
		Set<String> ips = playerToIpRegistry.getRegister(playerUuid);
		if (ips != null) {
			return ips;
		}
		
		if (!expensive) {
			return new HashSet<String>();
		}
		
		// Redis
		Jedis redis = ServiceLocator.getService(Jedis.class);
		if (redis != null) {
			String key = "pipapi-uuid-" + playerUuid.toString();
			long length = redis.llen(key).longValue();
			if (length > 0) {
				List<String> list = redis.lrange(key, 0L, length);
				ips = new HashSet<String>(list);
				playerToIpRegistry.setRegister(playerUuid, ips);
				return ips;
			}
		}
		
		// SQL
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
			return new HashSet<String>();
		}
		
		ips = retVal.get();
		if (redis != null) {
			String key = "pipapi-uuid-" + playerUuid.toString();
			for (String ip : ips) {
				redis.lpush(key, ip);
			}
		}
		playerToIpRegistry.setRegister(playerUuid, ips);
		
		return ips;
	}
	public Set<UUID> getPlayers(String ip) {
		return getPlayers(ip, true);
	}
	@SuppressWarnings("resource")
	public Set<UUID> getPlayers(String ip, boolean expensive) {
		if (ip == null) {
			throw new IllegalArgumentException("ip cannot be null.");
		}
		
		// Internal cache
		IRegistry<String, Set<UUID>> ipToPlayerRegistry = ServiceLocator.getService(IPToPlayerRegistry.class);
		Set<UUID> uuids = ipToPlayerRegistry.getRegister(ip);
		if (uuids != null) {
			return uuids;
		}
		
		if (!expensive) {
			return new HashSet<UUID>();
		}
		
		// Redis
		Jedis redis = ServiceLocator.getService(Jedis.class);
		if (redis != null) {
			String key = "pipapi-ip-" + ip;
			long length = redis.llen(key).longValue();
			if (length > 0) {
				List<String> list = redis.lrange(key, 0L, length);
				uuids = new HashSet<UUID>();
				for (String uuid : list) {
					uuids.add(UUID.fromString(uuid));
				}
				ipToPlayerRegistry.setRegister(ip, uuids);
				return uuids;
			}
		}
		
		// SQL
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
			return new HashSet<UUID>();
		}
		
		uuids = retVal.get();
		if (redis != null) {
			String key = "pipapi-ip-" + ip;
			for (UUID uuid : uuids) {
				redis.lpush(key, uuid.toString());
			}
		}
		ipToPlayerRegistry.setRegister(ip, uuids);
		
		return uuids;
	}
	
	//private
	
}

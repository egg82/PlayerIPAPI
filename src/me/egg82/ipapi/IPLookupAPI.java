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
import ninja.egg82.patterns.registries.IRegistry;
import ninja.egg82.patterns.registries.IVariableRegistry;
import ninja.egg82.utils.ThreadUtil;
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
	@SuppressWarnings("resource")
	public Set<String> getIps(UUID playerUuid, boolean expensive) {
		if (playerUuid == null) {
			throw new IllegalArgumentException("playerUuid cannot be null.");
		}
		
		// Internal cache
		IExpiringRegistry<UUID, Set<String>> playerToIpRegistry = ServiceLocator.getService(PlayerToIPRegistry.class);
		Set<String> ips = playerToIpRegistry.getRegister(playerUuid);
		if (ips != null) {
			fetchIpsBackground(playerUuid);
			return ips;
		}
		
		if (!expensive) {
			fetchIpsBackground(playerUuid);
			return new HashSet<String>();
		}
		
		// Redis
		Jedis redis = ServiceLocator.getService(Jedis.class);
		if (redis != null) {
			String key = "pipapi:uuid:" + playerUuid.toString();
			Set<String> list = redis.smembers(key);
			if (list.size() > 0) {
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
			String key = "pipapi:uuid:" + playerUuid.toString();
			for (String ip : ips) {
				redis.sadd(key, ip);
			}
		}
		playerToIpRegistry.setRegister(playerUuid, ips);
		
		Set<String> threadIps = ips;
		ThreadUtil.submit(new Runnable() {
			public void run() {
				IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
				JedisPool redisPool = ServiceLocator.getService(JedisPool.class);
				if (redisPool != null) {
					Jedis redis = redisPool.getResource();
					if (configRegistry.hasRegister("redis.pass")) {
						redis.auth(configRegistry.getRegister("redis.pass", String.class));
					}
					
					for (String ip : threadIps) {
						redis.publish("pipapi", playerUuid.toString() + "," + ip);
					}
					
					redis.close();
				}
			}
		});
		
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
		IExpiringRegistry<String, Set<UUID>> ipToPlayerRegistry = ServiceLocator.getService(IPToPlayerRegistry.class);
		Set<UUID> uuids = ipToPlayerRegistry.getRegister(ip);
		if (uuids != null) {
			fetchUuidsBackground(ip);
			return uuids;
		}
		
		if (!expensive) {
			fetchUuidsBackground(ip);
			return new HashSet<UUID>();
		}
		
		// Redis
		Jedis redis = ServiceLocator.getService(Jedis.class);
		if (redis != null) {
			String key = "pipapi:ip:" + ip;
			Set<String> list = redis.smembers(key);
			if (list.size() > 0) {
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
			String key = "pipapi:ip:" + ip;
			for (UUID uuid : uuids) {
				redis.sadd(key, uuid.toString());
			}
		}
		ipToPlayerRegistry.setRegister(ip, uuids);
		
		Set<UUID> threadUuids = uuids;
		ThreadUtil.submit(new Runnable() {
			public void run() {
				IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
				JedisPool redisPool = ServiceLocator.getService(JedisPool.class);
				if (redisPool != null) {
					Jedis redis = redisPool.getResource();
					if (configRegistry.hasRegister("redis.pass")) {
						redis.auth(configRegistry.getRegister("redis.pass", String.class));
					}
					
					for (UUID uuid : threadUuids) {
						redis.publish("pipapi", uuid.toString() + "," + ip);
					}
					
					redis.close();
				}
			}
		});
		
		return uuids;
	}
	
	//private
	@SuppressWarnings("resource")
	private void fetchIpsBackground(UUID playerUuid) {
		ThreadUtil.submit(new Runnable() {
			public void run() {
				IRegistry<UUID, Set<String>> playerToIpRegistry = ServiceLocator.getService(PlayerToIPRegistry.class);
				Set<String> ips = playerToIpRegistry.getRegister(playerUuid);
				if (ips == null) {
					return;
				}
				
				// Load from Redis in the background
				Jedis redis = ServiceLocator.getService(Jedis.class);
				if (redis != null) {
					String key = "pipapi:uuid:" + playerUuid.toString();
					Set<String> list = redis.smembers(key);
					ips.addAll(list);
				}
				
				// Load from SQL in the background
				if (redis == null) {
					SelectIpsCommand command = new SelectIpsCommand(playerUuid);
					
					BiConsumer<Object, IPEventArgs> sqlData = (s, e) -> {
						ips.addAll(e.getIps());
						command.onData().detatchAll();
					};
					
					command.onData().attach(sqlData);
					command.start();
				} else {
					String key = "pipapi:uuid:" + playerUuid.toString();
					SelectIpsCommand command = new SelectIpsCommand(playerUuid);
					
					BiConsumer<Object, IPEventArgs> sqlData = (s, e) -> {
						ips.addAll(e.getIps());
						
						IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
						JedisPool redisPool = ServiceLocator.getService(JedisPool.class);
						if (redisPool != null) {
							Jedis r = redisPool.getResource();
							if (configRegistry.hasRegister("redis.pass")) {
								redis.auth(configRegistry.getRegister("redis.pass", String.class));
							}
							
							Set<String> list = redis.smembers(key);
							for (String sqlIp : ips) {
								if (!list.contains(sqlIp)) {
									redis.sadd(key, sqlIp);
									r.publish("pipapi", playerUuid.toString() + "," + sqlIp);
								}
							}
							
							r.close();
						}
						
						command.onData().detatchAll();
					};
					
					command.onData().attach(sqlData);
					command.start();
				}
			}
		});
	}
	@SuppressWarnings("resource")
	private void fetchUuidsBackground(String ip) {
		ThreadUtil.submit(new Runnable() {
			public void run() {
				IRegistry<String, Set<UUID>> ipToPlayerRegistry = ServiceLocator.getService(IPToPlayerRegistry.class);
				Set<UUID> uuids = ipToPlayerRegistry.getRegister(ip);
				if (uuids == null) {
					return;
				}
				
				// Load from Redis in the background
				Jedis redis = ServiceLocator.getService(Jedis.class);
				if (redis != null) {
					String key = "pipapi:ip:" + ip;
					Set<String> list = redis.smembers(key);
					for (String u : list) {
						uuids.add(UUID.fromString(u));
					}
				}
				
				// Load from SQL in the background
				if (redis == null) {
					SelectUuidsCommand command = new SelectUuidsCommand(ip);
					
					BiConsumer<Object, UUIDEventArgs> sqlData = (s, e) -> {
						uuids.addAll(e.getUuids());
						command.onData().detatchAll();
					};
					
					command.onData().attach(sqlData);
					command.start();
				} else {
					String key = "pipapi:ip:" + ip;
					SelectUuidsCommand command = new SelectUuidsCommand(ip);
					
					BiConsumer<Object, UUIDEventArgs> sqlData = (s, e) -> {
						uuids.addAll(e.getUuids());
						
						IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
						JedisPool redisPool = ServiceLocator.getService(JedisPool.class);
						if (redisPool != null) {
							Jedis r = redisPool.getResource();
							if (configRegistry.hasRegister("redis.pass")) {
								redis.auth(configRegistry.getRegister("redis.pass", String.class));
							}
							
							Set<String> list = redis.smembers(key);
							for (UUID sqlUuid : uuids) {
								if (!list.contains(sqlUuid.toString())) {
									redis.sadd(key, ip);
									r.publish("pipapi", sqlUuid.toString() + "," + ip);
								}
							}
							
							r.close();
						}
						
						command.onData().detatchAll();
					};
					
					command.onData().attach(sqlData);
					command.start();
				}
			}
		});
	}
}

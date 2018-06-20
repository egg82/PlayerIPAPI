package me.egg82.ipapi.utils;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import me.egg82.ipapi.core.IPEventArgs;
import me.egg82.ipapi.core.UUIDEventArgs;
import me.egg82.ipapi.registries.IPToPlayerRegistry;
import me.egg82.ipapi.registries.PlayerToIPRegistry;
import me.egg82.ipapi.sql.SelectIpsCommand;
import me.egg82.ipapi.sql.SelectUuidsCommand;
import me.egg82.ipapi.sql.mysql.UpdateIPMySQLCommand;
import me.egg82.ipapi.sql.mysql.UpdateUUIDMySQLCommand;
import me.egg82.ipapi.sql.sqlite.UpdateIPSQLiteCommand;
import me.egg82.ipapi.sql.sqlite.UpdateUUIDSQLiteCommand;
import ninja.egg82.enums.BaseSQLType;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IExpiringRegistry;
import ninja.egg82.sql.ISQL;
import redis.clients.jedis.Jedis;

public class PlayerCacheUtil {
	//vars
	
	//constructor
	public PlayerCacheUtil() {
		
	}
	
	//public
	public static void addIp(UUID uuid, String ip, boolean force) {
		IExpiringRegistry<UUID, Set<String>> playerToIpRegistry = ServiceLocator.getService(PlayerToIPRegistry.class);
		Set<String> ips = null;
		
		// Add to internal cache
		if (force) {
			ips = playerToIpRegistry.getRegister(uuid);
			if (ips == null) {
				ips = new HashSet<String>();
				playerToIpRegistry.setRegister(uuid, ips);
			}
		} else {
			if (playerToIpRegistry.hasRegister(uuid)) {
				// Don't want to trigger exceptions from getTimeRemaining
				
				long expirationTime = playerToIpRegistry.getTimeRemaining(uuid);
				ips = playerToIpRegistry.getRegister(uuid);
				if (ips != null) {
					// Set expiration back- we don't want to constantly re-set expiration times or we'll have a bad time
					playerToIpRegistry.setRegisterExpiration(uuid, expirationTime, TimeUnit.MILLISECONDS);
					ips.add(ip);
				} else {
					ips = new HashSet<String>();
				}
			} else {
				ips = new HashSet<String>();
			}
		}
		
		ips.add(ip);
		
		try (Jedis redis = RedisUtil.getRedis()) {
			if (redis != null) {
				// Redis available. Add to it and load missing data
				String key = "pipapi:uuid:" + uuid.toString();
				Set<String> list = redis.smembers(key);
				ips.addAll(list);
				if (!list.contains(ip)) {
					redis.sadd(key, ip);
					redis.publish("pipapi", uuid.toString() + "," + ip);
				}
				
				// Load any missing data from SQL in the background, then save
				SelectIpsCommand command = new SelectIpsCommand(uuid);
				BiConsumer<Object, IPEventArgs> sqlData = (s, e) -> {
					Set<String> i = null;
					if (playerToIpRegistry.hasRegister(uuid)) {
						// Don't want to trigger exceptions from getTimeRemaining
						
						long expirationTime = playerToIpRegistry.getTimeRemaining(uuid);
						i = playerToIpRegistry.getRegister(uuid);
						if (i != null) {
							// Set expiration back- we don't want to constantly re-set expiration times or we'll have a bad time
							playerToIpRegistry.setRegisterExpiration(uuid, expirationTime, TimeUnit.MILLISECONDS);
							i.add(ip);
						} else {
							i = new HashSet<String>();
						}
					} else {
						i = new HashSet<String>();
					}
					
					// Add data from SQL
					i.addAll(e.getIps());
					
					// Add data from Redis and update Redis from SQL if needed
					Set<String> l = redis.smembers(key);
					for (String sqlIp : i) {
						if (!l.contains(sqlIp)) {
							redis.sadd(key, sqlIp);
							redis.publish("pipapi", uuid.toString() + "," + sqlIp);
						}
					}
					i.addAll(l);
					
					command.onData().detatchAll();
					
					// Save to SQL
					ISQL sql = ServiceLocator.getService(ISQL.class);
					if (force || sql.getType() != BaseSQLType.SQLite) {
						if (sql.getType() == BaseSQLType.MySQL) {
							new UpdateIPMySQLCommand(uuid, i).start();
						} else if (sql.getType() == BaseSQLType.SQLite) {
							new UpdateIPSQLiteCommand(uuid, i).start();
						}
					} else {
						// If we're not forcing, no need to update MySQL
						// But if it's SQLite we'll want to update anyway
						new UpdateIPSQLiteCommand(uuid, i).start();
					}
				};
				
				command.onData().attach(sqlData);
				command.start();
			} else {
				// Redis not available. Load missing data from SQL
				AtomicReference<Set<String>> retVal = new AtomicReference<Set<String>>(null);
				CountDownLatch latch = new CountDownLatch(1);
				
				BiConsumer<Object, IPEventArgs> sqlData = (s, e) -> {
					retVal.set(e.getIps());
					latch.countDown();
				};
				
				SelectIpsCommand command = new SelectIpsCommand(uuid);
				command.onData().attach(sqlData);
				command.start();
				
				try {
					latch.await();
				} catch (Exception ex) {
					ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
				}
				
				command.onData().detatch(sqlData);
				
				if (retVal.get() != null) {
					ips.addAll(retVal.get());
				}
				
				// Save to SQL
				ISQL sql = ServiceLocator.getService(ISQL.class);
				if (force || sql.getType() != BaseSQLType.SQLite) {
					if (sql.getType() == BaseSQLType.MySQL) {
						new UpdateIPMySQLCommand(uuid, ips).start();
					} else if (sql.getType() == BaseSQLType.SQLite) {
						new UpdateIPSQLiteCommand(uuid, ips).start();
					}
				} else {
					// If we're not forcing, no need to update MySQL
					// But if it's SQLite we'll want to update anyway
					new UpdateIPSQLiteCommand(uuid, ips).start();
				}
			}
		}
	}
	public static void addUuid(String ip, UUID uuid, boolean force) {
		IExpiringRegistry<String, Set<UUID>> ipToPlayerRegistry = ServiceLocator.getService(IPToPlayerRegistry.class);
		Set<UUID> uuids = null;
		
		// Add to internal cache
		if (force) {
			uuids = ipToPlayerRegistry.getRegister(ip);
			if (uuids == null) {
				uuids = new HashSet<UUID>();
				ipToPlayerRegistry.setRegister(ip, uuids);
			}
		} else {
			if (ipToPlayerRegistry.hasRegister(ip)) {
				// Don't want to trigger exceptions from getTimeRemaining
				
				long expirationTime = ipToPlayerRegistry.getTimeRemaining(ip);
				uuids = ipToPlayerRegistry.getRegister(ip);
				if (uuids != null) {
					// Set expiration back- we don't want to constantly re-set expiration times or we'll have a bad time
					ipToPlayerRegistry.setRegisterExpiration(ip, expirationTime, TimeUnit.MILLISECONDS);
				} else {
					uuids = new HashSet<UUID>();
				}
			} else {
				uuids = new HashSet<UUID>();
			}
		}
		
		uuids.add(uuid);
		
		try (Jedis redis = RedisUtil.getRedis()) {
			if (redis != null) {
				// Redis available. Add to it and load missing data
				String key = "pipapi:ip:" + ip;
				Set<String> list = redis.smembers(key);
				for (String u : list) {
					uuids.add(UUID.fromString(u));
				}
				if (!list.contains(uuid.toString())) {
					redis.sadd(key, uuid.toString());
					redis.publish("pipapi", uuid.toString() + "," + ip);
				}
				
				// Load any missing data from SQL in the background, then save
				SelectUuidsCommand command = new SelectUuidsCommand(ip);
				BiConsumer<Object, UUIDEventArgs> sqlData = (s, e) -> {
					Set<UUID> i = null;
					if (ipToPlayerRegistry.hasRegister(ip)) {
						// Don't want to trigger exceptions from getTimeRemaining
						
						long expirationTime = ipToPlayerRegistry.getTimeRemaining(ip);
						i = ipToPlayerRegistry.getRegister(ip);
						if (i != null) {
							// Set expiration back- we don't want to constantly re-set expiration times or we'll have a bad time
							ipToPlayerRegistry.setRegisterExpiration(ip, expirationTime, TimeUnit.MILLISECONDS);
							i.add(uuid);
						} else {
							i = new HashSet<UUID>();
						}
					} else {
						i = new HashSet<UUID>();
					}
					
					// Add data from SQL
					i.addAll(e.getUuids());
					
					// Add data from Redis and update Redis from SQL if needed
					Set<String> l = redis.smembers(key);
					for (UUID sqlUuid : i) {
						if (!l.contains(sqlUuid.toString())) {
							redis.sadd(key, sqlUuid.toString());
							redis.publish("pipapi", sqlUuid.toString() + "," + ip);
						}
					}
					for (String u : l) {
						i.add(UUID.fromString(u));
					}
					
					command.onData().detatchAll();
					
					// Save to SQL
					ISQL sql = ServiceLocator.getService(ISQL.class);
					if (force || sql.getType() != BaseSQLType.SQLite) {
						if (sql.getType() == BaseSQLType.MySQL) {
							new UpdateUUIDMySQLCommand(ip, i).start();
						} else if (sql.getType() == BaseSQLType.SQLite) {
							new UpdateUUIDSQLiteCommand(ip, i).start();
						}
					} else {
						// If we're not forcing, no need to update MySQL
						// But if it's SQLite we'll want to update anyway
						new UpdateUUIDSQLiteCommand(ip, i).start();
					}
				};
				
				command.onData().attach(sqlData);
				command.start();
			} else {
				// Redis not available. Load missing data from SQL
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
				
				if (retVal.get() != null) {
					uuids.addAll(retVal.get());
				}
				
				// Save to SQL
				ISQL sql = ServiceLocator.getService(ISQL.class);
				if (force || sql.getType() != BaseSQLType.SQLite) {
					if (sql.getType() == BaseSQLType.MySQL) {
						new UpdateUUIDMySQLCommand(ip, uuids).start();
					} else if (sql.getType() == BaseSQLType.SQLite) {
						new UpdateUUIDSQLiteCommand(ip, uuids).start();
					}
				} else {
					// If we're not forcing, no need to update MySQL
					// But if it's SQLite we'll want to update anyway
					new UpdateUUIDSQLiteCommand(ip, uuids).start();
				}
			}
		}
	}
	
	//private
	
}

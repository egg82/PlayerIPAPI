package me.egg82.ipapi.utils;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.json.simple.JSONObject;

import me.egg82.ipapi.core.IPData;
import me.egg82.ipapi.core.UUIDData;
import me.egg82.ipapi.core.UpdateEventArgs;
import me.egg82.ipapi.registries.IPToPlayerRegistry;
import me.egg82.ipapi.registries.PlayerToIPRegistry;
import me.egg82.ipapi.sql.mysql.UpdateDataMySQLCommand;
import me.egg82.ipapi.sql.sqlite.UpdateDataSQLiteCommand;
import ninja.egg82.enums.BaseSQLType;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IExpiringRegistry;
import ninja.egg82.plugin.messaging.IMessageHandler;
import ninja.egg82.sql.ISQL;
import ninja.egg82.utils.ThreadUtil;
import redis.clients.jedis.Jedis;

public class PlayerCacheUtil {
	//vars
	
	//constructor
	public PlayerCacheUtil() {
		
	}
	
	//public
	public static void addToCache(UUID uuid, String ip, long created, long updated, boolean doInsert) {
		if (!ValidationUtil.isValidIp(ip)) {
			return;
		}
		
		IExpiringRegistry<UUID, Set<IPData>> playerToIpRegistry = ServiceLocator.getService(PlayerToIPRegistry.class);
		Set<IPData> ips = null;
		
		// Add to UUID->IPs cache
		if (playerToIpRegistry.hasRegister(uuid)) {
			// Don't want to trigger exceptions from getTimeRemaining
			
			long expirationTime = playerToIpRegistry.getTimeRemaining(uuid);
			ips = playerToIpRegistry.getRegister(uuid);
			if (ips != null) {
				// Set expiration back- we don't want to constantly re-set expiration times or we'll have a bad time
				playerToIpRegistry.setRegisterExpiration(uuid, expirationTime, TimeUnit.MILLISECONDS);
			} else {
				// Create a dummy set to avoid NPEs
				ips = new HashSet<IPData>();
			}
		} else {
			// Create a dummy set to avoid NPEs
			ips = new HashSet<IPData>();
		}
		
		// Finally, add the new value (replace if already present)
		IPData ipData = new IPData(ip, created, updated);
		// Here we really just hope we don't run into any race conditions, because seriously
		ips.remove(ipData);
		ips.add(ipData);
		
		IExpiringRegistry<String, Set<UUIDData>> ipToPlayerRegistry = ServiceLocator.getService(IPToPlayerRegistry.class);
		Set<UUIDData> uuids = null;
		
		// Add to IP->UUIDs cache
		if (ipToPlayerRegistry.hasRegister(ip)) {
			// Don't want to trigger exceptions from getTimeRemaining
			
			long expirationTime = ipToPlayerRegistry.getTimeRemaining(ip);
			uuids = ipToPlayerRegistry.getRegister(ip);
			if (uuids != null) {
				// Set expiration back- we don't want to constantly re-set expiration times or we'll have a bad time
				ipToPlayerRegistry.setRegisterExpiration(ip, expirationTime, TimeUnit.MILLISECONDS);
			} else {
				// Create a dummy set to avoid NPEs
				uuids = new HashSet<UUIDData>();
			}
		} else {
			// Create a dummy set to avoid NPEs
			uuids = new HashSet<UUIDData>();
		}
		
		// Finally, add the new value (replace if already present)
		UUIDData uuidData = new UUIDData(uuid, created, updated);
		// Here we really just hope we don't run into any race conditions, because seriously
		uuids.remove(uuidData);
		uuids.add(uuidData);
		
		if (doInsert) {
			ISQL sql = ServiceLocator.getService(ISQL.class);
			// Update data in local tables if SQLite is used
			if (sql != null && sql.getType() == BaseSQLType.SQLite) {
				new UpdateDataSQLiteCommand(uuid, ip, created, updated).start();
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	public static void addInfo(UUID uuid, String ip) {
		if (!ValidationUtil.isValidIp(ip)) {
			return;
		}
		
		// Preemptively add to Redis to hopefully avoid race conditions. We'll update it again later
		try (Jedis redis = RedisUtil.getRedis()) {
			if (redis != null) {
				String uuidKey = "pipapi:uuid:" + uuid.toString();
				redis.sadd(uuidKey, ip);
				
				String ipKey = "pipapi:ip:" + ip;
				redis.sadd(ipKey, uuid.toString());
				
				Long time = Long.valueOf(System.currentTimeMillis());
				
				// Sadly we only update the "updated" param if the value exists so "SETNX" is useless here. We get to round-trip twice, but it's still faster than SQL
				String infoKey = "pipapi:info:" + uuid.toString() + ":" + ip;
				String info = redis.get(infoKey);
				
				if (info == null) {
					JSONObject infoObject = new JSONObject();
					infoObject.put("created", time);
					infoObject.put("updated", time);
					redis.set(infoKey, infoObject.toJSONString());
				} else {
					try {
						JSONObject infoObject = JSONUtil.parseObject(info);
						infoObject.put("updated", time);
						redis.set(infoKey, infoObject.toJSONString());
					} catch (Exception ex) {
						redis.del(infoKey);
						ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
						ex.printStackTrace();
					}
				}
			}
		}
		
		// Do work in new thread. This is ONLY safe from race conditions due to the fact that UpdateData SQL commands run a non-parallel insert query
		// Meaning even if a race condition were to occur, a SQL lookup would be used and the lookup would be blocked until the insert operation completed
		// This might, in rare cases, cause some extra lag with plugins that get data via the main thread, but would guarantee player logins don't cause the server to lag
		ThreadUtil.submit(new Runnable() {
			public void run() {
				// Add to SQL and get created/updated data back
				AtomicReference<UpdateEventArgs> retVal = new AtomicReference<UpdateEventArgs>(null);
				CountDownLatch latch = new CountDownLatch(1);
				
				BiConsumer<Object, UpdateEventArgs> sqlData = (s, e) -> {
					retVal.set(e);
					
					ISQL sql = ServiceLocator.getService(ISQL.class);
					if (sql.getType() == BaseSQLType.MySQL) {
						UpdateDataMySQLCommand c = (UpdateDataMySQLCommand) s;
						c.onUpdated().detatchAll();
					} else if (sql.getType() == BaseSQLType.SQLite) {
						UpdateDataSQLiteCommand c = (UpdateDataSQLiteCommand) s;
						c.onUpdated().detatchAll();
					}
					
					latch.countDown();
				};
				
				ISQL sql = ServiceLocator.getService(ISQL.class);
				if (sql.getType() == BaseSQLType.MySQL) {
					UpdateDataMySQLCommand command = new UpdateDataMySQLCommand(uuid, ip);
					command.onUpdated().attach(sqlData);
					command.start();
				} else if (sql.getType() == BaseSQLType.SQLite) {
					UpdateDataSQLiteCommand command = new UpdateDataSQLiteCommand(uuid, ip);
					command.onUpdated().attach(sqlData);
					command.start();
				}
				
				try {
					latch.await();
				} catch (Exception ex) {
					ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
					ex.printStackTrace();
				}
				
				if (retVal.get() == null || retVal.get().getUuid() == null || retVal.get().getIp() == null) {
					// Error occurred during SQL functions. We'll skip adding incomplete data because that seems like a bad idea
					return;
				}
				
				// Add to internal cache, if available
				addToCache(uuid, ip, retVal.get().getCreated(), retVal.get().getUpdated(), false);
				
				// Add to Redis and update other servers, if available
				try (Jedis redis = RedisUtil.getRedis()) {
					if (redis != null) {
						String infoKey = "pipapi:info:" + uuid.toString() + ":" + ip;
						JSONObject infoObject = new JSONObject();
						infoObject.put("created", Long.valueOf(retVal.get().getCreated()));
						infoObject.put("updated", Long.valueOf(retVal.get().getUpdated()));
						redis.set(infoKey, infoObject.toJSONString());
						
						redis.publish("pipapi", uuid.toString() + "," + ip + "," + retVal.get().getCreated() + "," + retVal.get().getUpdated());
					}
				}
				
				// Update other servers through Rabbit, if available
				if (ServiceLocator.hasService(IMessageHandler.class)) {
					PlayerChannelUtil.broadcastInfo(uuid, ip, retVal.get().getCreated(), retVal.get().getUpdated());
				}
			}
		});
	}
	
	//private
	
}

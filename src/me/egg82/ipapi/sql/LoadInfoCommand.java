package me.egg82.ipapi.sql;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import me.egg82.ipapi.registries.IPToPlayerRegistry;
import me.egg82.ipapi.registries.PlayerToIPRegistry;
import ninja.egg82.bukkit.services.ConfigRegistry;
import ninja.egg82.events.CompleteEventArgs;
import ninja.egg82.events.SQLEventArgs;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IExpiringRegistry;
import ninja.egg82.patterns.registries.IVariableRegistry;
import ninja.egg82.patterns.Command;
import ninja.egg82.sql.ISQL;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class LoadInfoCommand extends Command {
	//vars
	private ISQL sql = ServiceLocator.getService(ISQL.class);
	
	private UUID ipToPlayerQuery = null;
	private UUID playerToIpQuery = null;
	
	private BiConsumer<Object, SQLEventArgs> sqlError = (s, e) -> onSQLError(e);
	private BiConsumer<Object, SQLEventArgs> sqlData = (s, e) -> onSQLData(e);
	
	//constructor
	public LoadInfoCommand() {
		super();
		
		sql.onError().attach(sqlError);
		sql.onData().attach(sqlData);
	}
	
	//public
	
	//private
	protected void onExecute(long elapsedMilliseconds) {
		ipToPlayerQuery = sql.parallelQuery("SELECT `ip`, `uuids` FROM `ip_to_player`;");
	}
	
	@SuppressWarnings("resource")
	private void onSQLData(SQLEventArgs e) {
		if (e.getUuid().equals(ipToPlayerQuery)) {
			Exception lastEx = null;
			
			IExpiringRegistry<String, Set<UUID>> ipToPlayerRegistry = ServiceLocator.getService(IPToPlayerRegistry.class);
			JedisPool redisPool = ServiceLocator.getService(JedisPool.class);
			// Grab new Jedis instance
			Jedis redis = (redisPool != null) ? redisPool.getResource() : null;
			if (redis != null) {
				IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
				if (configRegistry.hasRegister("redis.pass")) {
					redis.auth(configRegistry.getRegister("redis.pass", String.class));
				}
			}
			for (Object[] o : e.getData().data) {
				try {
					String ip = (String) o[0];
					String[] uuids = ((String) o[1]).split(",\\s?");
					
					// Update Redis
					if (redis != null) {
						String key = "pipapi:ip:" + ip;
						Set<String> list = redis.smembers(key);
						for (String uuid : uuids) {
							if (!list.contains(uuid)) {
								redis.sadd(key, uuid);
							}
						}
					}
					
					long expirationTime = ipToPlayerRegistry.getTimeRemaining(ip);
					Set<UUID> register = ipToPlayerRegistry.getRegister(ip);
					if (register == null) {
						// Internal cache doesn't exist for this ip. Skip
						continue;
					}
					
					// Set expiration back- we don't want to constantly re-set expiration times or we'll have a bad time
					ipToPlayerRegistry.setRegisterExpiration(ip, expirationTime, TimeUnit.MILLISECONDS);
					
					// Update internal cache
					for (String uuid : uuids) {
						register.add(UUID.fromString(uuid));
					}
				} catch (Exception ex) {
					ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
					lastEx = ex;
				}
			}
			
			// Cleanup Jedis
			if (redis != null) {
				redis.close();
			}
			
			playerToIpQuery = sql.parallelQuery("SELECT `uuid`, `ips` FROM `player_to_ip`;");
			
			if (lastEx != null) {
				throw new RuntimeException(lastEx);
			}
		} else if (e.getUuid().equals(playerToIpQuery)) {
			Exception lastEx = null;
			
			IExpiringRegistry<UUID, Set<String>> playerToIpRegistry = ServiceLocator.getService(PlayerToIPRegistry.class);
			JedisPool redisPool = ServiceLocator.getService(JedisPool.class);
			// Grab new Jedis instance
			Jedis redis = (redisPool != null) ? redisPool.getResource() : null;
			if (redis != null) {
				IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
				if (configRegistry.hasRegister("redis.pass")) {
					redis.auth(configRegistry.getRegister("redis.pass", String.class));
				}
			}
			for (Object[] o : e.getData().data) {
				try {
					UUID uuid = UUID.fromString((String) o[0]);
					String[] ips = ((String) o[1]).split(",\\s?");
					
					// Update Redis
					if (redis != null) {
						String key = "pipapi:uuid:" + uuid.toString();
						Set<String> list = redis.smembers(key);
						for (String ip : ips) {
							if (!list.contains(ip)) {
								redis.sadd(key, ip);
							}
						}
					}
					
					long expirationTime = playerToIpRegistry.getTimeRemaining(uuid);
					Set<String> register = playerToIpRegistry.getRegister(uuid);
					if (register == null) {
						// Internal cache doesn't exist for this ip. Skip
						continue;
					}
					
					// Set expiration back- we don't want to constantly re-set expiration times or we'll have a bad time
					playerToIpRegistry.setRegisterExpiration(uuid, expirationTime, TimeUnit.MILLISECONDS);
					
					// Update internal cache
					for (String ip : ips) {
						register.add(ip);
					}
				} catch (Exception ex) {
					ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
					lastEx = ex;
				}
			}
			
			// Cleanup Jedis
			if (redis != null) {
				redis.close();
			}
			
			sql.onError().detatch(sqlError);
			sql.onData().detatch(sqlError);
			
			onComplete().invoke(this, CompleteEventArgs.EMPTY);
			
			if (lastEx != null) {
				throw new RuntimeException(lastEx);
			}
		}
	}
	private void onSQLError(SQLEventArgs e) {
		if (!e.getUuid().equals(ipToPlayerQuery) && !e.getUuid().equals(playerToIpQuery)) {
			return;
		}
		
		ServiceLocator.getService(IExceptionHandler.class).silentException(e.getSQLError().ex);
		
		sql.onError().detatch(sqlError);
		sql.onData().detatch(sqlError);
		
		onComplete().invoke(this, CompleteEventArgs.EMPTY);
		
		throw new RuntimeException(e.getSQLError().ex);
	}
}

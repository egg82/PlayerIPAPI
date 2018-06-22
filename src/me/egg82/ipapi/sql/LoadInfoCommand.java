package me.egg82.ipapi.sql;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.apache.commons.validator.routines.InetAddressValidator;

import me.egg82.ipapi.registries.IPToPlayerRegistry;
import me.egg82.ipapi.registries.PlayerToIPRegistry;
import me.egg82.ipapi.sql.mysql.UpdateIPMySQLCommand;
import me.egg82.ipapi.sql.mysql.UpdateUUIDMySQLCommand;
import me.egg82.ipapi.sql.sqlite.UpdateIPSQLiteCommand;
import me.egg82.ipapi.sql.sqlite.UpdateUUIDSQLiteCommand;
import me.egg82.ipapi.utils.RedisUtil;
import ninja.egg82.enums.BaseSQLType;
import ninja.egg82.events.CompleteEventArgs;
import ninja.egg82.events.SQLEventArgs;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IExpiringRegistry;
import ninja.egg82.patterns.Command;
import ninja.egg82.sql.ISQL;
import redis.clients.jedis.Jedis;

public class LoadInfoCommand extends Command {
	//vars
	private ISQL sql = ServiceLocator.getService(ISQL.class);
	
	private UUID ipToPlayerQuery = null;
	private UUID playerToIpQuery = null;
	
	private BiConsumer<Object, SQLEventArgs> sqlError = (s, e) -> onSQLError(e);
	private BiConsumer<Object, SQLEventArgs> sqlData = (s, e) -> onSQLData(e);
	
	private InetAddressValidator ipValidator = InetAddressValidator.getInstance();
	
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
	
	private void onSQLData(SQLEventArgs e) {
		if (e.getUuid().equals(ipToPlayerQuery)) {
			Exception lastEx = null;
			
			IExpiringRegistry<String, Set<UUID>> ipToPlayerRegistry = ServiceLocator.getService(IPToPlayerRegistry.class);
			try (Jedis redis = RedisUtil.getRedis()) {
				for (Object[] o : e.getData().data) {
					try {
						String ip = (String) o[0];
						Set<String> uuids = new HashSet<String>(Arrays.asList(((String) o[1]).split(",\\s?")));
						
						// Validate IP and remove bad data
						if (!ipValidator.isValid(ip)) {
							if (redis != null) {
								String key = "pipapi:ip:" + ip;
								redis.del(key);
							}
							sql.parallelQuery("DELETE FROM `ip_to_player` WHERE `ip`=?;", ip);
							
							continue;
						}
						
						// Update Redis
						if (redis != null) {
							String key = "pipapi:ip:" + ip;
							Set<String> list = redis.smembers(key);
							// Validate UUID and remove bad data
							for (String uuid : list) {
								if (ipValidator.isValid(uuid)) {
									redis.srem(key, uuid);
									sql.parallelQuery("UPDATE `ip_to_player` SET `uuids` = replace(`uuids`, ',?', '');", uuid);
									sql.parallelQuery("UPDATE `ip_to_player` SET `uuids` = replace(`uuids`, '?,', '');", uuid);
								}
							}
							for (String uuid : uuids) {
								if (ipValidator.isValid(uuid)) {
									// UUID is invalid, remove bad data
									redis.srem(key, uuid);
									list.remove(uuid);
									sql.parallelQuery("UPDATE `ip_to_player` SET `uuids` = replace(`uuids`, ',?', '');", uuid);
									sql.parallelQuery("UPDATE `ip_to_player` SET `uuids` = replace(`uuids`, '?,', '');", uuid);
								} else {
									if (!list.contains(uuid)) {
										redis.sadd(key, uuid);
									}
								}
							}
							// Verify Redis doesn't have data SQL is missing and update if needed
							if (uuids.addAll(list)) {
								Set<UUID> l = new HashSet<UUID>();
								for (String uuid : uuids) {
									if (!ipValidator.isValid(uuid)) {
										l.add(UUID.fromString(uuid));
									}
								}
								
								// Update SQL with new list
								if (sql.getType() == BaseSQLType.MySQL) {
									new UpdateUUIDMySQLCommand(ip, l).start();
								} else if (sql.getType() == BaseSQLType.SQLite) {
									new UpdateUUIDSQLiteCommand(ip, l).start();
								}
							}
						}
						
						if (!ipToPlayerRegistry.hasRegister(ip)) {
							// Don't want to trigger exceptions from getTimeRemaining
							continue;
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
							if (!ipValidator.isValid(uuid)) {
								register.add(UUID.fromString(uuid));
							}
						}
						for (Iterator<UUID> i = register.iterator(); i.hasNext();) {
							UUID uuid = i.next();
							if (!uuids.contains(uuid.toString())) {
								i.remove();
							}
						}
					} catch (Exception ex) {
						ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
						lastEx = ex;
					}
				}
			}
			
			playerToIpQuery = sql.parallelQuery("SELECT `uuid`, `ips` FROM `player_to_ip`;");
			
			if (lastEx != null) {
				throw new RuntimeException(lastEx);
			}
		} else if (e.getUuid().equals(playerToIpQuery)) {
			Exception lastEx = null;
			
			IExpiringRegistry<UUID, Set<String>> playerToIpRegistry = ServiceLocator.getService(PlayerToIPRegistry.class);
			try (Jedis redis = RedisUtil.getRedis()) {
				for (Object[] o : e.getData().data) {
					try {
						// Validate UUID and remove bad data
						if (ipValidator.isValid((String) o[0])) {
							if (redis != null) {
								String key = "pipapi:uuid:" + (String) o[0];
								redis.del(key);
							}
							sql.parallelQuery("DELETE FROM `player_to_ip` WHERE `uuid`=?;", (String) o[0]);
							
							continue;
						}
						
						UUID uuid = UUID.fromString((String) o[0]);
						Set<String> ips = new HashSet<String>(Arrays.asList(((String) o[1]).split(",\\s?")));
						
						// Update Redis
						if (redis != null) {
							String key = "pipapi:uuid:" + uuid.toString();
							Set<String> list = redis.smembers(key);
							// Validate IP and remove bad data
							for (String ip : list) {
								if (!ipValidator.isValid(ip)) {
									redis.srem(key, ip);
									sql.parallelQuery("UPDATE `player_to_ip` SET `uuids` = replace(`ips`, ',?', '');", ip);
									sql.parallelQuery("UPDATE `player_to_ip` SET `uuids` = replace(`ips`, '?,', '');", ip);
								}
							}
							for (String ip : ips) {
								if (!ipValidator.isValid(ip)) {
									// IP is invalid, remove bad data
									redis.srem(key, ip);
									list.remove(ip);
									sql.parallelQuery("UPDATE `player_to_ip` SET `uuids` = replace(`ips`, ',?', '');", ip);
									sql.parallelQuery("UPDATE `player_to_ip` SET `uuids` = replace(`ips`, '?,', '');", ip);
								} else {
									if (!list.contains(ip)) {
										redis.sadd(key, ip);
									}
								}
							}
							// Verify Redis doesn't have data SQL is missing and update if needed
							if (ips.addAll(list)) {
								// Update SQL with new list
								if (sql.getType() == BaseSQLType.MySQL) {
									new UpdateIPMySQLCommand(uuid, ips).start();
								} else if (sql.getType() == BaseSQLType.SQLite) {
									new UpdateIPSQLiteCommand(uuid, ips).start();
								}
							}
						}
						
						if (!playerToIpRegistry.hasRegister(uuid)) {
							// Don't want to trigger exceptions from getTimeRemaining
							continue;
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
							if (ipValidator.isValid(ip)) {
								register.add(ip);
							}
						}
						for (Iterator<String> i = register.iterator(); i.hasNext();) {
							String ip = i.next();
							if (!ips.contains(ip)) {
								i.remove();
							}
						}
					} catch (Exception ex) {
						ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
						lastEx = ex;
					}
				}
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

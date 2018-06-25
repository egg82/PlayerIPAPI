package me.egg82.ipapi.sql.mysql;

import java.sql.Timestamp;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.json.simple.JSONObject;

import me.egg82.ipapi.utils.PlayerCacheUtil;
import me.egg82.ipapi.utils.RedisUtil;
import me.egg82.ipapi.utils.ValidationUtil;
import ninja.egg82.enums.BaseSQLType;
import ninja.egg82.events.CompleteEventArgs;
import ninja.egg82.events.SQLEventArgs;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.Command;
import ninja.egg82.sql.ISQL;
import redis.clients.jedis.Jedis;

public class FetchQueueMySQLCommand extends Command {
	//vars
	private ISQL sql = ServiceLocator.getService(ISQL.class);
	
	private UUID fetchQuery = null;
	private UUID finalQuery = null;
	
	private BiConsumer<Object, SQLEventArgs> sqlError = (s, e) -> onSQLError(e);
	private BiConsumer<Object, SQLEventArgs> sqlData = (s, e) -> onSQLData(e);
	
	//constructor
	public FetchQueueMySQLCommand() {
		super();
		
		sql.onError().attach(sqlError);
		sql.onData().attach(sqlData);
	}
	
	//public
	
	//private
	protected void onExecute(long elapsedMilliseconds) {
		if (sql.getType() == BaseSQLType.SQLite) {
			sql.onError().detatch(sqlError);
			sql.onData().detatch(sqlError);
			
			onComplete().invoke(this, CompleteEventArgs.EMPTY);
			return;
		}
		
		fetchQuery = sql.parallelQuery("SELECT `uuid`, `ip`, `created`, `updated` FROM `playeripapi_queue` ORDER BY `updated` ASC;");
	}
	@SuppressWarnings("unchecked")
	private void onSQLData(SQLEventArgs e) {
		if (e.getUuid().equals(fetchQuery)) {
			Exception lastEx = null;
			
			try (Jedis redis = RedisUtil.getRedis()) {
				// Iterate rows
				for (Object[] o : e.getData().data) {
					try {
						// Validate UUID/IP and remove bad data
						if (!ValidationUtil.isValidUuid((String) o[0])) {
							if (redis != null) {
								String uuidKey = "pipapi:uuid:" + (String) o[0];
								String infoKey = "pipapi:info:" + (String) o[0] + ":" + (String) o[1];
								redis.del(uuidKey);
								redis.del(infoKey);
							}
							sql.parallelQuery("DELETE FROM `playeripapi_queue` WHERE `uuid`=? AND `ip`=?;", o[0], o[1]);
							
							continue;
						}
						if (!ValidationUtil.isValidIp((String) o[1])) {
							if (redis != null) {
								String ipKey = "pipapi:ip:" + (String) o[1];
								String infoKey = "pipapi:info:" + (String) o[0] + ":" + (String) o[1];
								redis.del(ipKey);
								redis.del(infoKey);
							}
							sql.parallelQuery("DELETE FROM `playeripapi_queue` WHERE `uuid`=? AND `ip`=?;", o[0], o[1]);
							
							continue;
						}
						
						// Grab all data and convert to more useful object types
						UUID uuid = UUID.fromString((String) o[0]);
						String ip = (String) o[1];
						long created = ((Timestamp) o[2]).getTime();
						long updated = ((Timestamp) o[3]).getTime();
						
						// Set Redis, if available
						if (redis != null) {
							String uuidKey = "pipapi:uuid:" + uuid.toString();
							redis.sadd(uuidKey, ip);
							
							String ipKey = "pipapi:ip:" + ip;
							redis.sadd(ipKey, uuid.toString());
							
							String infoKey = "pipapi:info:" + uuid.toString() + ":" + ip;
							JSONObject infoObject = new JSONObject();
							infoObject.put("created", Long.valueOf(created));
							infoObject.put("updated", Long.valueOf(updated));
							redis.set(infoKey, infoObject.toJSONString());
						}
						
						// Set cache, if available
						PlayerCacheUtil.addToCache(uuid, ip, created, updated, true);
					} catch (Exception ex) {
						ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
						ex.printStackTrace();
						lastEx = ex;
					}
				}
			}
			
			finalQuery = sql.parallelQuery("DELETE FROM `playeripapi_queue` WHERE `updated` <= CURRENT_TIMESTAMP() - INTERVAL 2 MINUTE;");
			
			if (lastEx != null) {
				throw new RuntimeException(lastEx);
			}
		} else if (e.getUuid().equals(finalQuery)) {
			sql.onError().detatch(sqlError);
			sql.onData().detatch(sqlError);
			
			onComplete().invoke(this, CompleteEventArgs.EMPTY);
		}
	}
	private void onSQLError(SQLEventArgs e) {
		if (!e.getUuid().equals(fetchQuery) && !e.getUuid().equals(finalQuery)) {
			return;
		}
		
		ServiceLocator.getService(IExceptionHandler.class).silentException(e.getSQLError().ex);
		// Wrap in a new exception and print to console. We wrap so we know where the error actually comes from
		new Exception(e.getSQLError().ex).printStackTrace();
		
		sql.onError().detatch(sqlError);
		sql.onData().detatch(sqlError);
		
		onComplete().invoke(this, CompleteEventArgs.EMPTY);
		
		throw new RuntimeException(e.getSQLError().ex);
	}
}

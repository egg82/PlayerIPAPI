package me.egg82.ipapi.sql.mysql;

import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import me.egg82.ipapi.core.UUIDData;
import me.egg82.ipapi.core.UUIDEventArgs;
import me.egg82.ipapi.utils.RedisUtil;
import me.egg82.ipapi.utils.ValidationUtil;
import ninja.egg82.events.SQLEventArgs;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.events.EventHandler;
import ninja.egg82.patterns.Command;
import ninja.egg82.sql.ISQL;
import redis.clients.jedis.Jedis;

public class SelectUUIDsMySQLCommand extends Command {
	//vars
	private ISQL sql = ServiceLocator.getService(ISQL.class);
	
	private UUID query = null;
	
	private String ip = null;
	
	private BiConsumer<Object, SQLEventArgs> sqlError = (s, e) -> onSQLError(e);
	private BiConsumer<Object, SQLEventArgs> sqlData = (s, e) -> onSQLData(e);
	
	private EventHandler<UUIDEventArgs> onData = new EventHandler<UUIDEventArgs>();
	
	//constructor
	public SelectUUIDsMySQLCommand(String ip) {
		super();
		
		this.ip = ip;
		
		sql.onError().attach(sqlError);
		sql.onData().attach(sqlData);
	}
	
	//public
	public EventHandler<UUIDEventArgs> onData() {
		return onData;
	}
	
	//private
	protected void onExecute(long elapsedMilliseconds) {
		query = sql.parallelQuery("SELECT `uuid`, `created`, `updated` FROM `playeripapi` WHERE `ip`=?;", ip);
	}
	
	private void onSQLData(SQLEventArgs e) {
		if (e.getUuid().equals(query)) {
			Exception lastEx = null;
			
			Set<UUIDData> retVal = new HashSet<UUIDData>();
			try (Jedis redis = RedisUtil.getRedis()) {
				// Iterate rows
				for (Object[] o : e.getData().data) {
					try {
						// Validate IP and remove bad data
						if (!ValidationUtil.isValidUuid((String) o[0])) {
							if (redis != null) {
								String uuidKey = "pipapi:uuid:" + (String) o[0];
								String infoKey = "pipapi:info:" + (String) o[0] + ":" + ip;
								redis.del(uuidKey);
								redis.del(infoKey);
							}
							sql.parallelQuery("DELETE FROM `playeripapi` WHERE `uuid`=? AND `ip`=?;", o[0], ip);
							
							continue;
						}
						
						// Grab all data and convert to more useful object types
						UUID uuid = UUID.fromString((String) o[0]);
						long created = ((Timestamp) o[1]).getTime();
						long updated = ((Timestamp) o[2]).getTime();
						
						// Add new data
						retVal.add(new UUIDData(uuid, created, updated));
					} catch (Exception ex) {
						ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
						ex.printStackTrace();
						lastEx = ex;
					}
				}
			}
			
			sql.onError().detatch(sqlError);
			sql.onData().detatch(sqlError);
			
			onData.invoke(this, new UUIDEventArgs(ip, retVal));
			
			if (lastEx != null) {
				throw new RuntimeException(lastEx);
			}
		}
	}
	private void onSQLError(SQLEventArgs e) {
		if (!e.getUuid().equals(query)) {
			return;
		}
		
		ServiceLocator.getService(IExceptionHandler.class).silentException(e.getSQLError().ex);
		// Wrap in a new exception and print to console. We wrap so we know where the error actually comes from
		new Exception(e.getSQLError().ex).printStackTrace();
		
		sql.onError().detatch(sqlError);
		sql.onData().detatch(sqlError);
		
		onData.invoke(this, new UUIDEventArgs(ip, new HashSet<UUIDData>()));
		
		throw new RuntimeException(e.getSQLError().ex);
	}
}

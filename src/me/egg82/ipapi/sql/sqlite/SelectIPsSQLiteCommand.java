package me.egg82.ipapi.sql.sqlite;

import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import me.egg82.ipapi.core.IPData;
import me.egg82.ipapi.core.IPEventArgs;
import me.egg82.ipapi.utils.RedisUtil;
import me.egg82.ipapi.utils.ValidationUtil;
import ninja.egg82.events.SQLEventArgs;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.events.EventHandler;
import ninja.egg82.patterns.Command;
import ninja.egg82.sql.ISQL;
import redis.clients.jedis.Jedis;

public class SelectIPsSQLiteCommand extends Command {
	//vars
	private ISQL sql = ServiceLocator.getService(ISQL.class);
	
	private UUID query = null;
	
	private UUID uuid = null;
	
	private BiConsumer<Object, SQLEventArgs> sqlError = (s, e) -> onSQLError(e);
	private BiConsumer<Object, SQLEventArgs> sqlData = (s, e) -> onSQLData(e);
	
	private EventHandler<IPEventArgs> onData = new EventHandler<IPEventArgs>();
	
	//constructor
	public SelectIPsSQLiteCommand(UUID uuid) {
		super();
		
		this.uuid = uuid;
		
		sql.onError().attach(sqlError);
		sql.onData().attach(sqlData);
	}
	
	//public
	public EventHandler<IPEventArgs> onData() {
		return onData;
	}
	
	//private
	protected void onExecute(long elapsedMilliseconds) {
		query = sql.parallelQuery("SELECT `ip`, `created`, `updated` FROM `playeripapi` WHERE `uuid`=?;", uuid.toString());
	}
	
	private void onSQLData(SQLEventArgs e) {
		if (e.getUuid().equals(query)) {
			Exception lastEx = null;
			
			Set<IPData> retVal = new HashSet<IPData>();
			try (Jedis redis = RedisUtil.getRedis()) {
				// Iterate rows
				for (Object[] o : e.getData().data) {
					try {
						// Validate IP and remove bad data
						if (!ValidationUtil.isValidIp((String) o[0])) {
							if (redis != null) {
								String ipKey = "pipapi:ip:" + (String) o[0];
								String infoKey = "pipapi:info:" + uuid.toString() + ":" + (String) o[0];
								redis.del(ipKey);
								redis.del(infoKey);
							}
							sql.parallelQuery("DELETE FROM `playeripapi` WHERE `uuid`=? AND `ip`=?;", uuid.toString(), o[0]);
							
							continue;
						}
						
						// Grab all data and convert to more useful object types
						String ip = (String) o[0];
						long created = Timestamp.valueOf((String) o[1]).getTime();
						long updated = Timestamp.valueOf((String) o[2]).getTime();
						
						// Add new data
						retVal.add(new IPData(ip, created, updated));
					} catch (Exception ex) {
						ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
						ex.printStackTrace();
						lastEx = ex;
					}
				}
			}
			
			sql.onError().detatch(sqlError);
			sql.onData().detatch(sqlError);
			
			onData.invoke(this, new IPEventArgs(uuid, retVal));
			
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
		
		onData.invoke(this, new IPEventArgs(uuid, new HashSet<IPData>()));
		
		throw new RuntimeException(e.getSQLError().ex);
	}
}

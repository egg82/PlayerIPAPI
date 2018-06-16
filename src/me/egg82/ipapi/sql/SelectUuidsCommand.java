package me.egg82.ipapi.sql;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import me.egg82.ipapi.core.UUIDEventArgs;
import ninja.egg82.events.SQLEventArgs;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.events.EventHandler;
import ninja.egg82.patterns.Command;
import ninja.egg82.sql.ISQL;

public class SelectUuidsCommand extends Command {
	//vars
	private ISQL sql = ServiceLocator.getService(ISQL.class);
	
	private UUID query = null;
	
	private String ip = null;
	
	private BiConsumer<Object, SQLEventArgs> sqlError = (s, e) -> onSQLError(e);
	private BiConsumer<Object, SQLEventArgs> sqlData = (s, e) -> onSQLData(e);
	
	private EventHandler<UUIDEventArgs> onData = new EventHandler<UUIDEventArgs>();
	
	//constructor
	public SelectUuidsCommand(String ip) {
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
		query = sql.parallelQuery("SELECT `ips` FROM `ip_to_player` WHERE `ip`=?;", ip);
	}
	
	private void onSQLData(SQLEventArgs e) {
		if (e.getUuid().equals(query)) {
			Exception lastEx = null;
			
			Set<UUID> retVal = new HashSet<UUID>();
			for (Object[] o : e.getData().data) {
				try {
					String[] uuids = ((String) o[0]).split(",\\s?");
					for (String uuid : uuids) {
						retVal.add(UUID.fromString(uuid));
					}
				} catch (Exception ex) {
					ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
					lastEx = ex;
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
		
		sql.onError().detatch(sqlError);
		sql.onData().detatch(sqlError);
		
		onData.invoke(this, new UUIDEventArgs(ip, new HashSet<UUID>()));
		
		throw new RuntimeException(e.getSQLError().ex);
	}
}

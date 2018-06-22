package me.egg82.ipapi.sql.mysql;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import ninja.egg82.events.SQLEventArgs;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.Command;
import ninja.egg82.sql.ISQL;

public class UpdateUUIDMySQLCommand extends Command {
	//vars
	private ISQL sql = ServiceLocator.getService(ISQL.class);
	
	private UUID query = null;
	
	private String ip = null;
	private String uuids = null;
	
	private BiConsumer<Object, SQLEventArgs> sqlError = (s, e) -> onSQLError(e);
	private BiConsumer<Object, SQLEventArgs> sqlData = (s, e) -> onSQLData(e);
	
	//constructor
	public UpdateUUIDMySQLCommand(String ip, Set<UUID> uuids) {
		super();
		
		this.ip = ip;
		
		Set<String> convertedUuids = new HashSet<String>();
		for (UUID uuid : uuids) {
			convertedUuids.add(uuid.toString());
		}
		this.uuids = String.join(",", convertedUuids);
		
		sql.onError().attach(sqlError);
		sql.onData().attach(sqlData);
	}
	
	//public
	
	//private
	protected void onExecute(long elapsedMilliseconds) {
		if (uuids.length() == 0) {
			sql.onError().detatch(sqlError);
			sql.onData().detatch(sqlError);
			return;
		}
		
		query = sql.query("INSERT INTO `ip_to_player` (`ip`, `uuids`) VALUES(?, ?) ON DUPLICATE KEY UPDATE `uuids`=?;", ip, uuids, uuids);
	}
	private void onSQLData(SQLEventArgs e) {
		if (!e.getUuid().equals(query)) {
			return;
		}
		
		sql.onError().detatch(sqlError);
		sql.onData().detatch(sqlError);
	}
	private void onSQLError(SQLEventArgs e) {
		if (!e.getUuid().equals(query)) {
			return;
		}
		
		ServiceLocator.getService(IExceptionHandler.class).silentException(e.getSQLError().ex);
		
		sql.onError().detatch(sqlError);
		sql.onData().detatch(sqlError);
		
		throw new RuntimeException(e.getSQLError().ex);
	}
}

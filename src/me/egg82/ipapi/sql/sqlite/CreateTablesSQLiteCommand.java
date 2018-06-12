package me.egg82.ipapi.sql.sqlite;

import java.util.UUID;
import java.util.function.BiConsumer;

import ninja.egg82.events.SQLEventArgs;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.Command;
import ninja.egg82.sql.ISQL;

public class CreateTablesSQLiteCommand extends Command {
	//vars
	private ISQL sql = ServiceLocator.getService(ISQL.class);
	
	private UUID playerToIpQuery = null;
	private UUID ipToPlayerQuery = null;
	
	private UUID finalQuery = null;
	
	private BiConsumer<Object, SQLEventArgs> sqlError = (s, e) -> onSQLError(e);
	private BiConsumer<Object, SQLEventArgs> sqlData = (s, e) -> onSQLData(e);
	
	//constructor
	public CreateTablesSQLiteCommand() {
		super();
		
		sql.onError().attach(sqlError);
		sql.onData().attach(sqlData);
	}
	
	//public
	
	//private
	protected void onExecute(long elapsedMilliseconds) {
		playerToIpQuery = sql.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='player_to_ip';");
		ipToPlayerQuery = sql.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='ip_to_player';");
	}
	
	private void onSQLData(SQLEventArgs e) {
		if (e.getUuid().equals(playerToIpQuery)) {
			if (e.getData().data.length > 0 && e.getData().data[0].length > 0 && ((Number) e.getData().data[0][0]).intValue() != 0) {
				return;
			}
			
			sql.query(
				"CREATE TABLE `player_to_ip` ("
						+ "`uuid` TEXT(255) NOT NULL PRIMARY KEY,"
						+ "`ips` TEXT(4294967295) NOT NULL"
				+ ");"
			);
		} else if (e.getUuid().equals(ipToPlayerQuery)) {
			if (e.getData().data.length > 0 && e.getData().data[0].length > 0 && ((Number) e.getData().data[0][0]).intValue() != 0) {
				sql.onError().detatch(sqlError);
				sql.onData().detatch(sqlError);
				return;
			}
			
			finalQuery = sql.query(
				"CREATE TABLE `ip_to_player` ("
						+ "`ip` TEXT(255) NOT NULL PRIMARY KEY,"
						+ "`uuids` TEXT(4294967295) NOT NULL"
				+ ");"
			);
		} else if (e.getUuid().equals(finalQuery)) {
			sql.onError().detatch(sqlError);
			sql.onData().detatch(sqlError);
		}
		
		if (!e.getUuid().equals(finalQuery)) {
			return;
		}
		
		sql.onError().detatch(sqlError);
		sql.onData().detatch(sqlError);
	}
	private void onSQLError(SQLEventArgs e) {
		ServiceLocator.getService(IExceptionHandler.class).silentException(e.getSQLError().ex);
		
		if (e.getUuid().equals(ipToPlayerQuery) || e.getUuid().equals(finalQuery)) {
			sql.onError().detatch(sqlError);
			sql.onData().detatch(sqlError);
		}
		
		throw new RuntimeException(e.getSQLError().ex);
	}
}

package me.egg82.ipapi.sql.mysql;

import java.util.UUID;
import java.util.function.BiConsumer;

import ninja.egg82.bukkit.services.ConfigRegistry;
import ninja.egg82.events.SQLEventArgs;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IVariableRegistry;
import ninja.egg82.patterns.Command;
import ninja.egg82.sql.ISQL;

public class CreateTablesMySQLCommand extends Command {
	//vars
	private ISQL sql = ServiceLocator.getService(ISQL.class);
	
	private UUID playerToIpQuery = null;
	private UUID ipToPlayerQuery = null;
	
	private UUID finalQuery = null;
	
	private BiConsumer<Object, SQLEventArgs> sqlError = (s, e) -> onSQLError(e);
	private BiConsumer<Object, SQLEventArgs> sqlData = (s, e) -> onSQLData(e);
	
	//constructor
	public CreateTablesMySQLCommand() {
		super();
		
		sql.onError().attach(sqlError);
		sql.onData().attach(sqlData);
	}
	
	//public
	
	//private
	protected void onExecute(long elapsedMilliseconds) {
		IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
		playerToIpQuery = sql.query("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='player_to_ip';", configRegistry.getRegister("mysql.database", String.class));
		ipToPlayerQuery = sql.query("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='ip_to_player';", configRegistry.getRegister("mysql.database", String.class));
	}
	private void onSQLData(SQLEventArgs e) {
		if (e.getUuid().equals(playerToIpQuery)) {
			if (e.getData().data.length > 0 && e.getData().data[0].length > 0 && ((Number) e.getData().data[0][0]).intValue() != 0) {
				return;
			}
			
			sql.query(
				"CREATE TABLE `player_to_ip` ("
						+ "`uuid` TINYTEXT NOT NULL,"
						+ "`ips` LONGTEXT NOT NULL"
				+ ");"
			);
			sql.query("ALTER TABLE `player_to_ip` ADD PRIMARY KEY (`uuid`(255));");
		} else if (e.getUuid().equals(ipToPlayerQuery)) {
			if (e.getData().data.length > 0 && e.getData().data[0].length > 0 && ((Number) e.getData().data[0][0]).intValue() != 0) {
				sql.onError().detatch(sqlError);
				sql.onData().detatch(sqlError);
				return;
			}
			
			sql.query(
				"CREATE TABLE `ip_to_player` ("
						+ "`ip` TINYTEXT NOT NULL,"
						+ "`uuids` LONGTEXT NOT NULL"
				+ ");"
			);
			finalQuery = sql.query("ALTER TABLE `ip_to_player` ADD PRIMARY KEY (`ip`(255));");
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

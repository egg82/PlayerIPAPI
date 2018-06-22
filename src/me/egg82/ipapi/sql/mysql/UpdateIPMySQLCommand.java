package me.egg82.ipapi.sql.mysql;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.apache.commons.validator.routines.InetAddressValidator;

import ninja.egg82.events.SQLEventArgs;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.Command;
import ninja.egg82.sql.ISQL;

public class UpdateIPMySQLCommand extends Command {
	//vars
	private ISQL sql = ServiceLocator.getService(ISQL.class);
	
	private UUID query = null;
	
	private UUID uuid = null;
	private String ips = null;
	
	private BiConsumer<Object, SQLEventArgs> sqlError = (s, e) -> onSQLError(e);
	private BiConsumer<Object, SQLEventArgs> sqlData = (s, e) -> onSQLData(e);
	
	private InetAddressValidator ipValidator = InetAddressValidator.getInstance();
	
	//constructor
	public UpdateIPMySQLCommand(UUID uuid, Set<String> ips) {
		super();
		
		Set<String> ips2 = new HashSet<String>(ips);
		for (Iterator<String> i = ips2.iterator(); i.hasNext();) {
			String ip = i.next();
			if (!ipValidator.isValid(ip)) {
				i.remove();
			}
		}
		
		this.uuid = uuid;
		this.ips = String.join(",", ips2);
		
		sql.onError().attach(sqlError);
		sql.onData().attach(sqlData);
	}
	
	//public
	
	//private
	protected void onExecute(long elapsedMilliseconds) {
		if (ips.length() == 0) {
			sql.onError().detatch(sqlError);
			sql.onData().detatch(sqlError);
			return;
		}
		
		query = sql.query("INSERT INTO `player_to_ip` (`uuid`, `ips`) VALUES(?, ?) ON DUPLICATE KEY UPDATE `ips`=?;", uuid.toString(), ips, ips);
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

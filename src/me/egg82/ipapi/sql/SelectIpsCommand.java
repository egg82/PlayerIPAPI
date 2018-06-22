package me.egg82.ipapi.sql;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.apache.commons.validator.routines.InetAddressValidator;

import me.egg82.ipapi.core.IPEventArgs;
import ninja.egg82.events.SQLEventArgs;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.events.EventHandler;
import ninja.egg82.patterns.Command;
import ninja.egg82.sql.ISQL;

public class SelectIpsCommand extends Command {
	//vars
	private ISQL sql = ServiceLocator.getService(ISQL.class);
	
	private UUID query = null;
	
	private UUID uuid = null;
	
	private BiConsumer<Object, SQLEventArgs> sqlError = (s, e) -> onSQLError(e);
	private BiConsumer<Object, SQLEventArgs> sqlData = (s, e) -> onSQLData(e);
	
	private EventHandler<IPEventArgs> onData = new EventHandler<IPEventArgs>();
	
	private InetAddressValidator ipValidator = InetAddressValidator.getInstance();
	
	//constructor
	public SelectIpsCommand(UUID uuid) {
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
		query = sql.parallelQuery("SELECT `ips` FROM `player_to_ip` WHERE `uuid`=?;", uuid.toString());
	}
	
	private void onSQLData(SQLEventArgs e) {
		if (e.getUuid().equals(query)) {
			Exception lastEx = null;
			
			Set<String> retVal = new HashSet<String>();
			for (Object[] o : e.getData().data) {
				try {
					String[] ips = ((String) o[0]).split(",\\s?");
					for (String ip : ips) {
						if (ipValidator.isValid(ip)) {
							retVal.add(ip);
						} else {
							sql.parallelQuery("UPDATE `player_to_ip` SET `ips` = replace(`ips`, ',?', '');", ip);
							sql.parallelQuery("UPDATE `player_to_ip` SET `ips` = replace(`ips`, '?,', '');", ip);
						}
					}
				} catch (Exception ex) {
					ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
					lastEx = ex;
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
		
		sql.onError().detatch(sqlError);
		sql.onData().detatch(sqlError);
		
		onData.invoke(this, new IPEventArgs(uuid, new HashSet<String>()));
		
		throw new RuntimeException(e.getSQLError().ex);
	}
}

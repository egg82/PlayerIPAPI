package me.egg82.ipapi.sql.mysql;

import java.sql.Timestamp;
import java.util.UUID;
import java.util.function.BiConsumer;

import me.egg82.ipapi.core.UpdateEventArgs;
import me.egg82.ipapi.utils.ValidationUtil;
import ninja.egg82.events.SQLEventArgs;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.events.EventHandler;
import ninja.egg82.patterns.Command;
import ninja.egg82.sql.ISQL;

public class UpdateDataMySQLCommand extends Command {
	//vars
	private ISQL sql = ServiceLocator.getService(ISQL.class);
	
	private UUID insertQuery = null;
	private UUID selectQuery = null;
	private UUID finalQuery = null;
	
	private UUID uuid = null;
	private String ip = null;
	
	private BiConsumer<Object, SQLEventArgs> sqlError = (s, e) -> onSQLError(e);
	private BiConsumer<Object, SQLEventArgs> sqlData = (s, e) -> onSQLData(e);
	
	private EventHandler<UpdateEventArgs> updated = new EventHandler<UpdateEventArgs>();
	
	//constructor
	public UpdateDataMySQLCommand(UUID uuid, String ip) {
		super();
		
		this.uuid = uuid;
		this.ip = ip;
		
		sql.onError().attach(sqlError);
		sql.onData().attach(sqlData);
	}
	
	//public
	public EventHandler<UpdateEventArgs> onUpdated() {
		return updated;
	}
	
	//private
	protected void onExecute(long elapsedMilliseconds) {
		if (!ValidationUtil.isValidIp(ip)) {
			sql.onError().detatch(sqlError);
			sql.onData().detatch(sqlError);
			return;
		}
		
		insertQuery = sql.query("INSERT INTO `playeripapi` (`uuid`, `ip`) VALUES(?, ?) ON DUPLICATE KEY UPDATE `updated`=CURRENT_TIMESTAMP();", uuid.toString(), ip);
	}
	private void onSQLData(SQLEventArgs e) {
		if (e.getUuid().equals(insertQuery)) {
			selectQuery = sql.parallelQuery("SELECT `created`, `updated` FROM `playeripapi` WHERE `uuid`=? AND `ip`=?;", uuid.toString(), ip);
		} else if (e.getUuid().equals(selectQuery)) {
			Exception lastEx = null;
			
			Timestamp created = null;
			Timestamp updated = null;
			
			for (Object[] o : e.getData().data) {
				try {
					created = (Timestamp) o[0];
					updated = (Timestamp) o[1];
				} catch (Exception ex) {
					ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
					ex.printStackTrace();
					lastEx = ex;
				}
			}
			
			if (created != null && updated != null) {
				finalQuery = sql.parallelQuery("INSERT INTO `playeripapi_queue` (`uuid`, `ip`, `created`, `updated`) VALUES(?, ?, ?, ?) ON DUPLICATE KEY UPDATE `updated`=?;", uuid.toString(), ip, created, updated, updated);
				onUpdated().invoke(this, new UpdateEventArgs(uuid, ip, created.getTime(), updated.getTime()));
			} else {
				sql.onError().detatch(sqlError);
				sql.onData().detatch(sqlError);
				onUpdated().invoke(this, UpdateEventArgs.EMPTY);
			}
			
			if (lastEx != null) {
				throw new RuntimeException(lastEx);
			}
		} else if (e.getUuid().equals(finalQuery)) {
			sql.onError().detatch(sqlError);
			sql.onData().detatch(sqlError);
		}
	}
	private void onSQLError(SQLEventArgs e) {
		if (!e.getUuid().equals(insertQuery) && !e.getUuid().equals(selectQuery) && !e.getUuid().equals(finalQuery)) {
			return;
		}
		
		ServiceLocator.getService(IExceptionHandler.class).silentException(e.getSQLError().ex);
		// Wrap in a new exception and print to console. We wrap so we know where the error actually comes from
		new Exception(e.getSQLError().ex).printStackTrace();
		
		if (e.getUuid().equals(selectQuery) || e.getUuid().equals(finalQuery)) {
			sql.onError().detatch(sqlError);
			sql.onData().detatch(sqlError);
		}
		
		if (e.getUuid().equals(insertQuery) || e.getUuid().equals(selectQuery)) {
			onUpdated().invoke(this, UpdateEventArgs.EMPTY);
		}
		
		throw new RuntimeException(e.getSQLError().ex);
	}
}

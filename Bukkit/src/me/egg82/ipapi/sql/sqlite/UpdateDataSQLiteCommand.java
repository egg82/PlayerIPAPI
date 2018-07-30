package me.egg82.ipapi.sql.sqlite;

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

public class UpdateDataSQLiteCommand extends Command {
	//vars
	private ISQL sql = ServiceLocator.getService(ISQL.class);
	
	private UUID insertQuery = null;
	private UUID finalQuery = null;
	
	private UUID uuid = null;
	private String ip = null;
	private long createdTime = -1L;
	private long updatedTime = -1L;
	
	private BiConsumer<Object, SQLEventArgs> sqlError = (s, e) -> onSQLError(e);
	private BiConsumer<Object, SQLEventArgs> sqlData = (s, e) -> onSQLData(e);
	
	private EventHandler<UpdateEventArgs> updated = new EventHandler<UpdateEventArgs>();
	
	//constructor
	public UpdateDataSQLiteCommand(UUID uuid, String ip) {
		super();
		
		this.uuid = uuid;
		this.ip = ip;
		
		sql.onError().attach(sqlError);
		sql.onData().attach(sqlData);
	}
	public UpdateDataSQLiteCommand(UUID uuid, String ip, long created, long updated) {
		super();
		
		this.uuid = uuid;
		this.ip = ip;
		this.createdTime = created;
		this.updatedTime = updated;
		
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
		
		if (createdTime == -1L && updatedTime == -1L) {
			insertQuery = sql.query("INSERT OR REPLACE INTO `playeripapi` (`uuid`, `ip`, `updated`) VALUES (?, ?, CURRENT_TIMESTAMP);", uuid.toString(), ip);
		} else {
			insertQuery = sql.query("INSERT OR REPLACE INTO `playeripapi` (`uuid`, `ip`, `created`, `updated`) VALUES (?, ?, ?, ?);", uuid.toString(), ip, new Timestamp(createdTime), new Timestamp(updatedTime));
		}
	}
	private void onSQLData(SQLEventArgs e) {
		if (e.getUuid().equals(insertQuery)) {
			finalQuery = sql.parallelQuery("SELECT `created`, `updated` FROM `playeripapi` WHERE `uuid`=? AND `ip`=?;", uuid.toString(), ip);
		} else if (e.getUuid().equals(finalQuery)) {
			if (createdTime != -1L && updatedTime != -1L) {
				sql.onError().detatch(sqlError);
				sql.onData().detatch(sqlError);
				onUpdated().invoke(this, new UpdateEventArgs(uuid, ip, createdTime, updatedTime));
				return;
			}
			
			Exception lastEx = null;
			
			long created = -1L;
			long updated = -1L;
			
			for (Object[] o : e.getData().data) {
				try {
					created = Timestamp.valueOf((String) o[0]).getTime();
					updated = Timestamp.valueOf((String) o[1]).getTime();
				} catch (Exception ex) {
					ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
					ex.printStackTrace();
					lastEx = ex;
				}
			}
			
			sql.onError().detatch(sqlError);
			sql.onData().detatch(sqlError);
			
			onUpdated().invoke(this, new UpdateEventArgs(uuid, ip, created, updated));
			
			if (lastEx != null) {
				throw new RuntimeException(lastEx);
			}
		}
	}
	private void onSQLError(SQLEventArgs e) {
		if (!e.getUuid().equals(insertQuery) && !e.getUuid().equals(finalQuery)) {
			return;
		}
		
		ServiceLocator.getService(IExceptionHandler.class).silentException(e.getSQLError().ex);
		// Wrap in a new exception and print to console. We wrap so we know where the error actually comes from
		new Exception(e.getSQLError().ex).printStackTrace();
		
		sql.onError().detatch(sqlError);
		sql.onData().detatch(sqlError);
		
		onUpdated().invoke(this, UpdateEventArgs.EMPTY);
		
		throw new RuntimeException(e.getSQLError().ex);
	}
}

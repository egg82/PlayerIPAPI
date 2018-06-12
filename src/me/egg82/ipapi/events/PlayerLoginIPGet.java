package me.egg82.ipapi.events;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;

import me.egg82.ipapi.core.IPEventArgs;
import me.egg82.ipapi.core.UUIDEventArgs;
import me.egg82.ipapi.registries.IPToPlayerRegistry;
import me.egg82.ipapi.registries.PlayerToIPRegistry;
import me.egg82.ipapi.sql.SelectIpsCommand;
import me.egg82.ipapi.sql.SelectUuidsCommand;
import me.egg82.ipapi.sql.mysql.UpdateIPMySQLCommand;
import me.egg82.ipapi.sql.mysql.UpdateUUIDMySQLCommand;
import me.egg82.ipapi.sql.sqlite.UpdateIPSQLiteCommand;
import me.egg82.ipapi.sql.sqlite.UpdateUUIDSQLiteCommand;
import ninja.egg82.enums.BaseSQLType;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IRegistry;
import ninja.egg82.plugin.handlers.events.LowEventHandler;
import ninja.egg82.sql.ISQL;
import redis.clients.jedis.Jedis;

public class PlayerLoginIPGet extends LowEventHandler<PlayerJoinEvent> {
	//vars
	private IRegistry<UUID, Set<String>> playerToIpRegistry = ServiceLocator.getService(PlayerToIPRegistry.class);
	private IRegistry<String, Set<UUID>> ipToPlayerRegistry = ServiceLocator.getService(IPToPlayerRegistry.class);
	
	//constructor
	public PlayerLoginIPGet() {
		super();
	}
	
	//public
	
	//private
	protected void onExecute(long elapsedMilliseconds) {
		UUID uuid = event.getPlayer().getUniqueId();
		String ip = getIp(event.getPlayer());
		
		if (ip == null || ip.isEmpty()) {
			return;
		}
		
		setIp(uuid, ip);
		setUuid(ip, uuid);
	}
	
	private String getIp(Player player) {
		if (player == null) {
			return null;
		}
		
		InetSocketAddress socket = player.getAddress();
		
		if (socket == null) {
			return null;
		}
		
		InetAddress address = socket.getAddress();
		if (address == null) {
			return null;
		}
		
		return address.getHostAddress();
	}
	
	@SuppressWarnings("resource")
	private void setIp(UUID uuid, String ip) {
		boolean created = false;
		
		Set<String> ips = playerToIpRegistry.getRegister(uuid);
		if (ips != null) {
			if (!ips.add(ip)) {
				return;
			}
		} else {
			ips = new HashSet<String>();
			playerToIpRegistry.setRegister(uuid, ips);
			ips.add(ip);
			created = true;
		}
		
		Jedis redis = ServiceLocator.getService(Jedis.class);
		if (redis != null) {
			String key = "pipapi-uuid-" + uuid.toString();
			if (created) {
				long length = redis.llen(key).longValue();
				if (length > 0) {
					List<String> list = redis.lrange(key, 0L, length);
					ips.addAll(list);
				}
			}
			redis.lpush(key, ip);
		}
		
		if (created) {
			if (redis == null) {
				AtomicReference<Set<String>> retVal = new AtomicReference<Set<String>>(null);
				CountDownLatch latch = new CountDownLatch(1);
				
				BiConsumer<Object, IPEventArgs> sqlData = (s, e) -> {
					retVal.set(e.getIps());
					latch.countDown();
				};
				
				SelectIpsCommand command = new SelectIpsCommand(uuid);
				command.onData().attach(sqlData);
				command.start();
				
				try {
					latch.await();
				} catch (Exception ex) {
					ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
				}
				
				command.onData().detatch(sqlData);
				
				if (retVal.get() != null) {
					ips.addAll(retVal.get());
				}
			} else {
				String key = "pipapi-uuid-" + uuid.toString();
				SelectIpsCommand command = new SelectIpsCommand(uuid);
				
				BiConsumer<Object, IPEventArgs> sqlData = (s, e) -> {
					Set<String> i = playerToIpRegistry.getRegister(uuid);
					if (i == null) {
						i = new HashSet<String>();
						playerToIpRegistry.setRegister(uuid, i);
					}
					i.addAll(e.getIps());
					
					long length = redis.llen(key).longValue();
					if (length > 0) {
						List<String> list = redis.lrange(key, 0L, length);
						for (String sqlIp : i) {
							if (!list.contains(sqlIp)) {
								redis.lpush(key, sqlIp);
							}
						}
					}
					
					command.onData().detatchAll();
				};
				
				command.onData().attach(sqlData);
				command.start();
			}
		}
		
		ISQL sql = ServiceLocator.getService(ISQL.class);
		if (sql.getType() == BaseSQLType.MySQL) {
			new UpdateIPMySQLCommand(uuid, ips).start();
		} else if (sql.getType() == BaseSQLType.SQLite) {
			new UpdateIPSQLiteCommand(uuid, ips).start();
		}
	}
	@SuppressWarnings("resource")
	private void setUuid(String ip, UUID uuid) {
		boolean created = false;
		
		Set<UUID> uuids = ipToPlayerRegistry.getRegister(ip);
		if (uuids != null) {
			if (!uuids.add(uuid)) {
				return;
			}
		} else {
			uuids = new HashSet<UUID>();
			ipToPlayerRegistry.setRegister(ip, uuids);
			uuids.add(uuid);
			created = true;
		}
		
		Jedis redis = ServiceLocator.getService(Jedis.class);
		if (redis != null) {
			String key = "pipapi-ip-" + ip;
			if (created) {
				long length = redis.llen(key).longValue();
				if (length > 0) {
					List<String> list = redis.lrange(key, 0L, length);
					for (String u : list) {
						uuids.add(UUID.fromString(u));
					}
				}
			}
			redis.lpush(key, uuid.toString());
		}
		
		if (created) {
			if (redis == null) {
				AtomicReference<Set<UUID>> retVal = new AtomicReference<Set<UUID>>(null);
				CountDownLatch latch = new CountDownLatch(1);
				
				BiConsumer<Object, UUIDEventArgs> sqlData = (s, e) -> {
					retVal.set(e.getUuids());
					latch.countDown();
				};
				
				SelectUuidsCommand command = new SelectUuidsCommand(ip);
				command.onData().attach(sqlData);
				command.start();
				
				try {
					latch.await();
				} catch (Exception ex) {
					ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
				}
				
				command.onData().detatch(sqlData);
				
				if (retVal.get() != null) {
					uuids.addAll(retVal.get());
				}
			} else {
				String key = "pipapi-ip-" + ip;
				SelectUuidsCommand command = new SelectUuidsCommand(ip);
				
				BiConsumer<Object, UUIDEventArgs> sqlData = (s, e) -> {
					Set<UUID> i = ipToPlayerRegistry.getRegister(ip);
					if (i == null) {
						i = new HashSet<UUID>();
						ipToPlayerRegistry.setRegister(ip, i);
					}
					i.addAll(e.getUuids());
					
					long length = redis.llen(key).longValue();
					if (length > 0) {
						List<String> list = redis.lrange(key, 0L, length);
						for (UUID sqlUuid : i) {
							if (!list.contains(sqlUuid.toString())) {
								redis.lpush(key, ip);
							}
						}
					}
					
					command.onData().detatchAll();
				};
				
				command.onData().attach(sqlData);
				command.start();
			}
		}
		
		ISQL sql = ServiceLocator.getService(ISQL.class);
		if (sql.getType() == BaseSQLType.MySQL) {
			new UpdateUUIDMySQLCommand(ip, uuids).start();
		} else if (sql.getType() == BaseSQLType.SQLite) {
			new UpdateUUIDSQLiteCommand(ip, uuids).start();
		}
	}
}

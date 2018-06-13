package me.egg82.ipapi.events;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
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
import me.egg82.ipapi.utils.PlayerChannelUtil;
import ninja.egg82.bukkit.services.ConfigRegistry;
import ninja.egg82.enums.BaseSQLType;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IRegistry;
import ninja.egg82.patterns.registries.IVariableRegistry;
import ninja.egg82.plugin.handlers.events.LowEventHandler;
import ninja.egg82.plugin.messaging.IMessageHandler;
import ninja.egg82.sql.ISQL;
import ninja.egg82.utils.ThreadUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class PlayerLoginIPGet extends LowEventHandler<PlayerJoinEvent> {
	//vars
	
	//constructor
	public PlayerLoginIPGet() {
		super();
	}
	
	//public
	
	//private
	@SuppressWarnings("resource")
	protected void onExecute(long elapsedMilliseconds) {
		UUID uuid = event.getPlayer().getUniqueId();
		String ip = getIp(event.getPlayer());
		
		if (ip == null || ip.isEmpty()) {
			return;
		}
		
		setIp(uuid, ip);
		setUuid(ip, uuid);
		
		if (ServiceLocator.hasService(IMessageHandler.class)) {
			PlayerChannelUtil.broadcastInfo(uuid, ip);
		}
		
		ThreadUtil.submit(new Runnable() {
			public void run() {
				IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
				JedisPool redisPool = ServiceLocator.getService(JedisPool.class);
				if (redisPool != null) {
					Jedis redis = redisPool.getResource();
					if (configRegistry.hasRegister("redis.pass")) {
						redis.auth(configRegistry.getRegister("redis.pass", String.class));
					}
					redis.publish("pipapi", uuid.toString() + "," + ip);
					redis.close();
				}
			}
		});
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
		IRegistry<UUID, Set<String>> playerToIpRegistry = ServiceLocator.getService(PlayerToIPRegistry.class);
		
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
			String key = "pipapi:uuid:" + uuid.toString();
			if (created) {
				Set<String> list = redis.smembers(key);
				ips.addAll(list);
			}
			redis.sadd(key, ip);
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
				String key = "pipapi:uuid:" + uuid.toString();
				SelectIpsCommand command = new SelectIpsCommand(uuid);
				
				BiConsumer<Object, IPEventArgs> sqlData = (s, e) -> {
					Set<String> i = playerToIpRegistry.getRegister(uuid);
					if (i == null) {
						i = new HashSet<String>();
						playerToIpRegistry.setRegister(uuid, i);
					}
					i.addAll(e.getIps());
					
					IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
					JedisPool redisPool = ServiceLocator.getService(JedisPool.class);
					if (redisPool != null) {
						Jedis r = redisPool.getResource();
						if (configRegistry.hasRegister("redis.pass")) {
							redis.auth(configRegistry.getRegister("redis.pass", String.class));
						}
						
						Set<String> list = redis.smembers(key);
						for (String sqlIp : i) {
							if (!list.contains(sqlIp)) {
								redis.sadd(key, sqlIp);
								r.publish("pipapi", uuid.toString() + "," + sqlIp);
							}
						}
						
						r.close();
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
		IRegistry<String, Set<UUID>> ipToPlayerRegistry = ServiceLocator.getService(IPToPlayerRegistry.class);
		
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
			String key = "pipapi:ip:" + ip;
			if (created) {
				Set<String> list = redis.smembers(key);
				for (String u : list) {
					uuids.add(UUID.fromString(u));
				}
			}
			redis.sadd(key, uuid.toString());
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
				String key = "pipapi:ip:" + ip;
				SelectUuidsCommand command = new SelectUuidsCommand(ip);
				
				BiConsumer<Object, UUIDEventArgs> sqlData = (s, e) -> {
					Set<UUID> i = ipToPlayerRegistry.getRegister(ip);
					if (i == null) {
						i = new HashSet<UUID>();
						ipToPlayerRegistry.setRegister(ip, i);
					}
					i.addAll(e.getUuids());
					
					IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
					JedisPool redisPool = ServiceLocator.getService(JedisPool.class);
					if (redisPool != null) {
						Jedis r = redisPool.getResource();
						if (configRegistry.hasRegister("redis.pass")) {
							redis.auth(configRegistry.getRegister("redis.pass", String.class));
						}
						
						Set<String> list = redis.smembers(key);
						for (UUID sqlUuid : i) {
							if (!list.contains(sqlUuid.toString())) {
								redis.sadd(key, ip);
								r.publish("pipapi", uuid.toString() + "," + ip);
							}
						}
						
						r.close();
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

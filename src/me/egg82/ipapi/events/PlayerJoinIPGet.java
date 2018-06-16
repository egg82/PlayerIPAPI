package me.egg82.ipapi.events;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;

import me.egg82.ipapi.utils.PlayerCacheUtil;
import me.egg82.ipapi.utils.PlayerChannelUtil;
import ninja.egg82.bukkit.services.ConfigRegistry;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IVariableRegistry;
import ninja.egg82.plugin.handlers.events.LowEventHandler;
import ninja.egg82.plugin.messaging.IMessageHandler;
import ninja.egg82.utils.ThreadUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class PlayerJoinIPGet extends LowEventHandler<PlayerJoinEvent> {
	//vars
	
	//constructor
	public PlayerJoinIPGet() {
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
		
		PlayerCacheUtil.addIp(uuid, ip, true);
		PlayerCacheUtil.addUuid(ip, uuid, true);
		
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
}

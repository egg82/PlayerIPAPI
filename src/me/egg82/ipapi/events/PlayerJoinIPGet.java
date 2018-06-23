package me.egg82.ipapi.events;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;

import me.egg82.ipapi.utils.PlayerCacheUtil;
import ninja.egg82.plugin.handlers.events.LowEventHandler;

public class PlayerJoinIPGet extends LowEventHandler<PlayerJoinEvent> {
	//vars
	
	//constructor
	public PlayerJoinIPGet() {
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
		
		PlayerCacheUtil.addInfo(uuid, ip);
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

package me.egg82.ipapi.utils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.UUID;

import ninja.egg82.plugin.utils.ChannelUtil;
import ninja.egg82.utils.UUIDUtil;

public class PlayerChannelUtil {
	//vars
	
	//constructor
	public PlayerChannelUtil() {
		
	}
	
	//public
	public static void broadcastInfo(UUID uuid, String ip) {
		if (uuid == null) {
			throw new IllegalArgumentException("uuid cannot be null.");
		}
		if (ip == null) {
			throw new IllegalArgumentException("ip cannot be null.");
		}
		
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(stream);
		
		try {
			out.write(UUIDUtil.toBytes(uuid));
			out.writeUTF(ip);
		} catch (Exception ex) {
			
		}
		
		ChannelUtil.broadcastToServers("IPAPIPlayerInfo", stream.toByteArray());
	}
	
	//private
	
}

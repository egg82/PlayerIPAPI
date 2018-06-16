package me.egg82.ipapi.messages;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.UUID;

import me.egg82.ipapi.utils.PlayerCacheUtil;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.plugin.handlers.async.AsyncMessageHandler;
import ninja.egg82.plugin.messaging.IMessageHandler;
import ninja.egg82.utils.UUIDUtil;

public class PlayerInfoMessage extends AsyncMessageHandler {
	//vars
	
	//constructor
	public PlayerInfoMessage() {
		super();
	}
	
	//public
	
	//private
	@SuppressWarnings("resource")
	protected void onExecute(long elapsedMilliseconds) {
		if (!channelName.equals("IPAPIPlayerInfo")) {
			return;
		}
		
		IMessageHandler messageHandler = ServiceLocator.getService(IMessageHandler.class);
		if (this.getSender().equals(messageHandler.getSenderId())) {
			return;
		}
		
		ByteArrayInputStream stream = new ByteArrayInputStream(data);
		DataInputStream in = new DataInputStream(stream);
		
		UUID uuid = null;
		String ip = null;
		
		try {
			uuid = UUIDUtil.readUuid(in);
			ip = in.readUTF();
		} catch (Exception ex) {
			ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
			throw new RuntimeException(ex);
		}
		
		PlayerCacheUtil.addIp(uuid, ip, false);
		PlayerCacheUtil.addUuid(ip, uuid, false);
	}
}

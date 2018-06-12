package me.egg82.ipapi.core;

import java.util.Set;
import java.util.UUID;

import ninja.egg82.patterns.events.EventArgs;

public class UUIDEventArgs extends EventArgs {
	//vars
	private String ip = null;
	private Set<UUID> uuids = null;
	
	//constructor
	public UUIDEventArgs(String ip, Set<UUID> uuids) {
		this.ip = ip;
		this.uuids = uuids;
	}
	
	//public
	public String getIp() {
		return ip;
	}
	public Set<UUID> getUuids() {
		return uuids;
	}
	
	//private
	
}

package me.egg82.ipapi.core;

import java.util.Set;
import java.util.UUID;

import ninja.egg82.patterns.events.EventArgs;

public class IPEventArgs extends EventArgs {
	//vars
	private UUID uuid = null;
	private Set<String> ips = null;
	
	//constructor
	public IPEventArgs(UUID uuid, Set<String> ips) {
		this.uuid = uuid;
		this.ips = ips;
	}
	
	//public
	public UUID getUuid() {
		return uuid;
	}
	public Set<String> getIps() {
		return ips;
	}
	
	//private
	
}

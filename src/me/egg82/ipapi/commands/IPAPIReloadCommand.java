package me.egg82.ipapi.commands;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import me.egg82.ipapi.Loaders;
import me.egg82.ipapi.registries.IPToPlayerRegistry;
import me.egg82.ipapi.registries.PlayerToIPRegistry;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.plugin.handlers.CommandHandler;
import ninja.egg82.sql.ISQL;
import redis.clients.jedis.Jedis;

public class IPAPIReloadCommand extends CommandHandler {
	//vars
	
	//constructor
	public IPAPIReloadCommand() {
		super();
	}
	
	//public
	
	//private
	protected void onExecute(long elapsedMilliseconds) {
		if (args.length != 0) {
			sender.sendMessage(ChatColor.RED + "Incorrect command usage!");
			String name = getClass().getSimpleName();
			name = name.substring(0, name.length() - 7).toLowerCase();
			Bukkit.getServer().dispatchCommand((CommandSender) sender.getHandle(), "? " + name);
			return;
		}
		
		// Memory cache
		ServiceLocator.removeServices(IPToPlayerRegistry.class);
		ServiceLocator.provideService(IPToPlayerRegistry.class);
		ServiceLocator.removeServices(PlayerToIPRegistry.class);
		ServiceLocator.provideService(PlayerToIPRegistry.class);
		
		// Redis
		Jedis redis = ServiceLocator.getService(Jedis.class);
		if (redis != null) {
			redis.close();
		}
		
		Loaders.loadRedis();
		
		// SQL
		List<ISQL> sqls = ServiceLocator.removeServices(ISQL.class);
		for (ISQL sql : sqls) {
			sql.disconnect();
		}
		
		Loaders.loadStorage();
		
		sender.sendMessage(ChatColor.GREEN + "Configuration reloaded!");
	}
	protected void onUndo() {
		
	}
}

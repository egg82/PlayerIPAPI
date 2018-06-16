package me.egg82.ipapi.commands;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import me.egg82.ipapi.Loaders;
import me.egg82.ipapi.core.RedisSubscriber;
import me.egg82.ipapi.registries.IPToPlayerRegistry;
import me.egg82.ipapi.registries.PlayerToIPRegistry;
import ninja.egg82.bukkit.services.ConfigRegistry;
import ninja.egg82.bukkit.utils.YamlUtil;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IVariableRegistry;
import ninja.egg82.plugin.handlers.CommandHandler;
import ninja.egg82.plugin.messaging.IMessageHandler;
import ninja.egg82.sql.ISQL;
import ninja.egg82.utils.FileUtil;
import ninja.egg82.utils.ThreadUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class IPAPIReloadCommand extends CommandHandler {
	//vars
	
	//constructor
	public IPAPIReloadCommand() {
		super();
	}
	
	//public
	
	//private
	@SuppressWarnings("resource")
	protected void onExecute(long elapsedMilliseconds) {
		if (args.length != 0) {
			sender.sendMessage(ChatColor.RED + "Incorrect command usage!");
			String name = getClass().getSimpleName();
			name = name.substring(0, name.length() - 7).toLowerCase();
			Bukkit.getServer().dispatchCommand((CommandSender) sender.getHandle(), "? " + name);
			return;
		}
		
		// Config
		ServiceLocator.getService(ConfigRegistry.class).load(YamlUtil.getOrLoadDefaults(ServiceLocator.getService(Plugin.class).getDataFolder().getAbsolutePath() + FileUtil.DIRECTORY_SEPARATOR_CHAR + "config.yml", "config.yml", true));
		
		// Memory cache
		ServiceLocator.removeServices(IPToPlayerRegistry.class);
		ServiceLocator.provideService(IPToPlayerRegistry.class);
		ServiceLocator.removeServices(PlayerToIPRegistry.class);
		ServiceLocator.provideService(PlayerToIPRegistry.class);
		
		// Redis
		JedisPool redisPool = ServiceLocator.getService(JedisPool.class);
		if (redisPool != null) {
			redisPool.close();
		}
		
		Loaders.loadRedis();
		
		ThreadUtil.submit(new Runnable() {
			public void run() {
				IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
				JedisPool redisPool = ServiceLocator.getService(JedisPool.class);
				if (redisPool != null) {
					Jedis redis = redisPool.getResource();
					if (configRegistry.hasRegister("redis.pass")) {
						redis.auth(configRegistry.getRegister("redis.pass", String.class));
					}
					redis.subscribe(new RedisSubscriber(), "pipapi");
				}
			}
		});
		
		// Rabbit
		List<IMessageHandler> services = ServiceLocator.removeServices(IMessageHandler.class);
		for (IMessageHandler handler : services) {
			try {
				handler.close();
			} catch (Exception ex) {
				
			}
		}
		
		Loaders.loadRabbit();
		
		if (ServiceLocator.hasService(IMessageHandler.class)) {
			ServiceLocator.getService(IMessageHandler.class).addHandlersFromPackage("me.egg82.ipapi.messages");
		}
		
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

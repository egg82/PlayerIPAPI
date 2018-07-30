package me.egg82.ipapi.commands;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import me.egg82.ipapi.Configuration;
import me.egg82.ipapi.Loaders;
import me.egg82.ipapi.core.RedisSubscriber;
import me.egg82.ipapi.registries.IPToPlayerRegistry;
import me.egg82.ipapi.registries.PlayerToIPRegistry;
import me.egg82.ipapi.utils.RedisUtil;
import ninja.egg82.bukkit.BasePlugin;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.plugin.enums.SenderType;
import ninja.egg82.plugin.handlers.CommandHandler;
import ninja.egg82.plugin.messaging.IMessageHandler;
import ninja.egg82.plugin.utils.DirectoryUtil;
import ninja.egg82.sql.ISQL;
import ninja.egg82.utils.ThreadUtil;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
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
		File configFile = new File(ServiceLocator.getService(Plugin.class).getDataFolder(), "config.yml");
		if (configFile.exists() && configFile.isDirectory()) {
			DirectoryUtil.delete(configFile);
		}
		if (!configFile.exists()) {
			try (InputStreamReader reader = new InputStreamReader(ServiceLocator.getService(Plugin.class).getResource("config.yml")); BufferedReader in = new BufferedReader(reader); FileWriter writer = new FileWriter(configFile); BufferedWriter out = new BufferedWriter(writer)) {
				while (in.ready()) {
					writer.write(in.readLine());
				}
			} catch (Exception ex) {
				
			}
		}
		
		ConfigurationLoader<ConfigurationNode> loader = YAMLConfigurationLoader.builder().setIndent(2).setFile(configFile).build();
		ConfigurationNode root = null;
		try {
			root = loader.load();
		} catch (Exception ex) {
			throw new RuntimeException("Error loading config. Aborting plugin load.", ex);
		}
		Configuration config = new Configuration(root);
		ServiceLocator.removeServices(Configuration.class);
		ServiceLocator.provideService(config);
		
		// Memory caches
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
				try (Jedis redis = RedisUtil.getRedis()) {
					if (redis != null) {
						redis.subscribe(new RedisSubscriber(), "pipapi");
					}
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
		
		Loaders.loadRabbit(ServiceLocator.getService(Plugin.class).getDescription().getName(), Bukkit.getServerName(), ServiceLocator.getService(BasePlugin.class).getServerId(), SenderType.SERVER);
		
		if (ServiceLocator.hasService(IMessageHandler.class)) {
			ServiceLocator.getService(IMessageHandler.class).addHandlersFromPackage("me.egg82.ipapi.messages");
		}
		
		// SQL
		List<ISQL> sqls = ServiceLocator.removeServices(ISQL.class);
		for (ISQL sql : sqls) {
			sql.disconnect();
		}
		
		Loaders.loadStorage(ServiceLocator.getService(Plugin.class).getDescription().getName(), getClass().getClassLoader(), ServiceLocator.getService(Plugin.class).getDataFolder());
		
		sender.sendMessage(ChatColor.GREEN + "Configuration reloaded!");
	}
	protected void onUndo() {
		
	}
}

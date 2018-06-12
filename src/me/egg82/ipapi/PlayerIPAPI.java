package me.egg82.ipapi;

import java.util.List;
import java.util.logging.Level;

import org.bukkit.ChatColor;

import ninja.egg82.bukkit.BasePlugin;
import ninja.egg82.bukkit.processors.CommandProcessor;
import ninja.egg82.bukkit.processors.EventProcessor;
import ninja.egg82.bukkit.services.ConfigRegistry;
import ninja.egg82.bukkit.utils.YamlUtil;
import ninja.egg82.exceptionHandlers.GameAnalyticsExceptionHandler;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.exceptionHandlers.RollbarExceptionHandler;
import ninja.egg82.exceptionHandlers.builders.GameAnalyticsBuilder;
import ninja.egg82.exceptionHandlers.builders.RollbarBuilder;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.plugin.messaging.IMessageHandler;
import ninja.egg82.plugin.utils.PluginReflectUtil;
import ninja.egg82.sql.ISQL;
import ninja.egg82.utils.FileUtil;
import ninja.egg82.utils.ThreadUtil;
import redis.clients.jedis.Jedis;

public class PlayerIPAPI extends BasePlugin {
	//vars
	private static IPLookupAPI api = new IPLookupAPI();
	
	private int numMessages = 0;
	private int numCommands = 0;
	private int numEvents = 0;
	private int numTicks = 0;
	
	private IExceptionHandler exceptionHandler = null;
	private String version = getDescription().getVersion();
	
	//constructor
	public PlayerIPAPI() {
		super();
		
		getLogger().setLevel(Level.WARNING);
		IExceptionHandler oldExceptionHandler = ServiceLocator.getService(IExceptionHandler.class);
		ServiceLocator.removeServices(IExceptionHandler.class);
		
		ServiceLocator.provideService(RollbarExceptionHandler.class, false);
		exceptionHandler = ServiceLocator.getService(IExceptionHandler.class);
		oldExceptionHandler.disconnect();
		exceptionHandler.connect(new RollbarBuilder("7e3b3bafbe934f4fb526a5ef86c9d002", "production", version, getServerId()), "PlayerIPAPI");
		exceptionHandler.setUnsentExceptions(oldExceptionHandler.getUnsentExceptions());
		exceptionHandler.setUnsentLogs(oldExceptionHandler.getUnsentLogs());
	}
	
	//public
	public static IPLookupAPI getAPI() {
		return api;
	}
	
	public void onLoad() {
		super.onLoad();
		
		PluginReflectUtil.addServicesFromPackage("me.egg82.ipapi.registries", true);
		PluginReflectUtil.addServicesFromPackage("me.egg82.ipapi.lists", true);
		
		ServiceLocator.getService(ConfigRegistry.class).load(YamlUtil.getOrLoadDefaults(getDataFolder().getAbsolutePath() + FileUtil.DIRECTORY_SEPARATOR_CHAR + "config.yml", "config.yml", true));
		
		Loaders.loadRedis();
		Loaders.loadStorage();
	}
	
	public void onEnable() {
		super.onEnable();
		
		numCommands = ServiceLocator.getService(CommandProcessor.class).addHandlersFromPackage("me.egg82.ipapi.commands", PluginReflectUtil.getCommandMapFromPackage("me.egg82.ipapi.commands", false, null, "Command"), false);
		numEvents = ServiceLocator.getService(EventProcessor.class).addHandlersFromPackage("me.egg82.ipapi.events");
		numMessages = ServiceLocator.getService(IMessageHandler.class).addHandlersFromPackage("me.egg82.ipapi.messages");
		numTicks = PluginReflectUtil.addServicesFromPackage("me.egg82.ipapi.ticks", false);
		
		enableMessage();
		
		ThreadUtil.rename(getName());
		ThreadUtil.scheduleAtFixedRate(checkExceptionLimitReached, 0L, 60L * 60L * 1000L);
	}
	public void onDisable() {
		super.onDisable();
		
		ThreadUtil.shutdown(1000L);
		
		List<ISQL> sqls = ServiceLocator.removeServices(ISQL.class);
		for (ISQL sql : sqls) {
			sql.disconnect();
		}
		
		Jedis redis = ServiceLocator.getService(Jedis.class);
		if (redis != null) {
			redis.close();
		}
		
		List<IMessageHandler> services = ServiceLocator.removeServices(IMessageHandler.class);
		for (IMessageHandler handler : services) {
			try {
				handler.close();
			} catch (Exception ex) {
				
			}
		}
		
		ServiceLocator.getService(CommandProcessor.class).clear();
		ServiceLocator.getService(EventProcessor.class).clear();
		
		disableMessage();
	}
	
	//private
	private Runnable checkExceptionLimitReached = new Runnable() {
		public void run() {
			if (exceptionHandler.isLimitReached()) {
				IExceptionHandler oldExceptionHandler = ServiceLocator.getService(IExceptionHandler.class);
				ServiceLocator.removeServices(IExceptionHandler.class);
				
				ServiceLocator.provideService(GameAnalyticsExceptionHandler.class, false);
				exceptionHandler = ServiceLocator.getService(IExceptionHandler.class);
				oldExceptionHandler.disconnect();
				exceptionHandler.connect(new GameAnalyticsBuilder("4ff5c2fc8cfe961796b534c34e9e7c56", "d983e137af030743434ed578472fdc2cba327ceb", version, getServerId()), getName());
				exceptionHandler.setUnsentExceptions(oldExceptionHandler.getUnsentExceptions());
				exceptionHandler.setUnsentLogs(oldExceptionHandler.getUnsentLogs());
			}
		}
	};
	
	private void enableMessage() {
		printInfo(ChatColor.AQUA + "PlayerIPAPI enabled.");
		printInfo(ChatColor.GREEN + "[Version " + getDescription().getVersion() + "] " + ChatColor.RED + numCommands + " commands " + ChatColor.LIGHT_PURPLE + numEvents + " events " + ChatColor.YELLOW + numTicks + " tick handlers " + ChatColor.BLUE + numMessages + " message handlers");
		printInfo(ChatColor.WHITE + "[PlayerIPAPI] " + ChatColor.GRAY + "Attempting to load compatibility with Bukkit version " + getGameVersion());
	}
	private void disableMessage() {
		printInfo(ChatColor.GREEN + "--== " + ChatColor.LIGHT_PURPLE + "PlayerIPAPI Disabled" + ChatColor.GREEN + " ==--");
	}
}

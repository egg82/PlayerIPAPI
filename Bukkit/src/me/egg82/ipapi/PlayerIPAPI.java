package me.egg82.ipapi;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import me.egg82.ipapi.core.RedisSubscriber;
import me.egg82.ipapi.sql.mysql.FetchQueueMySQLCommand;
import me.egg82.ipapi.utils.RedisUtil;
import ninja.egg82.bukkit.BasePlugin;
import ninja.egg82.bukkit.processors.CommandProcessor;
import ninja.egg82.bukkit.processors.EventProcessor;
import ninja.egg82.events.CompleteEventArgs;
import ninja.egg82.exceptionHandlers.GameAnalyticsExceptionHandler;
import ninja.egg82.exceptionHandlers.IExceptionHandler;
import ninja.egg82.exceptionHandlers.RollbarExceptionHandler;
import ninja.egg82.exceptionHandlers.builders.GameAnalyticsBuilder;
import ninja.egg82.exceptionHandlers.builders.RollbarBuilder;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.plugin.enums.SenderType;
import ninja.egg82.plugin.messaging.IMessageHandler;
import ninja.egg82.plugin.utils.PluginReflectUtil;
import ninja.egg82.sql.ISQL;
import ninja.egg82.utils.ThreadUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

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
		
		ConfigLoader.getConfig("config.yml", "config.yml");
	}
	
	public void onEnable() {
		super.onEnable();
		
		List<IMessageHandler> services = ServiceLocator.removeServices(IMessageHandler.class);
		for (IMessageHandler handler : services) {
			try {
				handler.close();
			} catch (Exception ex) {
				
			}
		}
		
		Loaders.loadRedis();
		Loaders.loadRabbit(getDescription().getName(), Bukkit.getServerName(), getServerId(), SenderType.SERVER);
		Loaders.loadStorage(getDescription().getName(), getClass().getClassLoader(), getDataFolder());
		
		numCommands = ServiceLocator.getService(CommandProcessor.class).addHandlersFromPackage("me.egg82.ipapi.commands", PluginReflectUtil.getCommandMapFromPackage("me.egg82.ipapi.commands", false, null, "Command"), false);
		numEvents = ServiceLocator.getService(EventProcessor.class).addHandlersFromPackage("me.egg82.ipapi.events");
		if (ServiceLocator.hasService(IMessageHandler.class)) {
			numMessages = ServiceLocator.getService(IMessageHandler.class).addHandlersFromPackage("me.egg82.ipapi.messages");
		}
		numTicks = PluginReflectUtil.addServicesFromPackage("me.egg82.ipapi.ticks", false);
		
		ThreadUtil.submit(new Runnable() {
			public void run() {
				try (Jedis redis = RedisUtil.getRedis()) {
					if (redis != null) {
						redis.subscribe(new RedisSubscriber(), "pipapi");
					}
				}
			}
		});
		
		enableMessage();
		
		ThreadUtil.rename(getName());
		ThreadUtil.schedule(checkExceptionLimitReached, 60L * 60L * 1000L);
		ThreadUtil.schedule(onFetchQueueThread, 10L * 1000L);
	}
	@SuppressWarnings("resource")
	public void onDisable() {
		super.onDisable();
		
		ThreadUtil.shutdown(1000L);
		
		List<ISQL> sqls = ServiceLocator.removeServices(ISQL.class);
		for (ISQL sql : sqls) {
			sql.disconnect();
		}
		
		JedisPool jedisPool = ServiceLocator.getService(JedisPool.class);
		if (jedisPool != null) {
			jedisPool.close();
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
	private Runnable onFetchQueueThread = new Runnable() {
		public void run() {
			CountDownLatch latch = new CountDownLatch(1);
			
			BiConsumer<Object, CompleteEventArgs<?>> complete = (s, e) -> {
				latch.countDown();
			};
			
			FetchQueueMySQLCommand command = new FetchQueueMySQLCommand();
			command.onComplete().attach(complete);
			command.start();
			
			try {
				latch.await();
			} catch (Exception ex) {
				ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
			}
			
			command.onComplete().detatch(complete);
			
			ThreadUtil.schedule(onFetchQueueThread, 10L * 1000L);
		}
	};
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
			
			ThreadUtil.schedule(checkExceptionLimitReached, 60L * 60L * 1000L);
		}
	};
	
	private void enableMessage() {
		printInfo(ChatColor.GREEN + "Enabled.");
		printInfo(ChatColor.AQUA + "[Version " + getDescription().getVersion() + "] " + ChatColor.DARK_GREEN + numCommands + " commands " + ChatColor.LIGHT_PURPLE + numEvents + " events " + ChatColor.GOLD + numTicks + " tick handlers " + ChatColor.BLUE + numMessages + " message handlers");
		printInfo("Attempting to load compatibility with Bukkit version " + getGameVersion());
	}
	private void disableMessage() {
		printInfo(ChatColor.RED + "Disabled");
	}
}

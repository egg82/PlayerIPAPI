package me.egg82.ipapi;

import java.io.File;

import me.egg82.ipapi.sql.mysql.CreateTablesMySQLCommand;
import me.egg82.ipapi.sql.sqlite.CreateTablesSQLiteCommand;
import ninja.egg82.bukkit.BasePlugin;
import ninja.egg82.bukkit.services.ConfigRegistry;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IVariableRegistry;
import ninja.egg82.sql.ISQL;
import ninja.egg82.sql.MySQL;
import ninja.egg82.sql.SQLite;
import redis.clients.jedis.Jedis;

public class Loaders {
	//vars
	
	//constructor
	public Loaders() {
		
	}
	
	//public
	public static void loadStorage() {
		BasePlugin plugin = ServiceLocator.getService(BasePlugin.class);
		IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
		ISQL sql = null;
		
		if (
			configRegistry.hasRegister("mysql.address")
			&& configRegistry.hasRegister("mysql.port")
			&& configRegistry.hasRegister("mysql.user")
			&& configRegistry.hasRegister("mysql.pass")
			&& configRegistry.hasRegister("mysql.database")
			&& configRegistry.hasRegister("mysql.threads")
		) {
			sql = new MySQL(configRegistry.getRegister("mysql.threads", Number.class).intValue(), plugin.getName(), plugin.getClass().getClassLoader());
			sql.connect(
				configRegistry.getRegister("mysql.address", String.class),
				configRegistry.getRegister("mysql.port", Number.class).intValue(),
				configRegistry.getRegister("mysql.user", String.class),
				configRegistry.getRegister("mysql.pass", String.class),
				configRegistry.getRegister("mysql.database", String.class)
			);
			ServiceLocator.provideService(sql);
			new CreateTablesMySQLCommand().start();
		} else if (
			configRegistry.hasRegister("sqlite.file")
			&& configRegistry.hasRegister("sqlite.threads")
		) {
			sql = new SQLite(configRegistry.getRegister("sqlite.threads", Number.class).intValue(), plugin.getName(), plugin.getClass().getClassLoader());
			sql.connect(new File(plugin.getDataFolder(), configRegistry.getRegister("sqlite.file", String.class)).getAbsolutePath());
			ServiceLocator.provideService(sql);
			new CreateTablesSQLiteCommand().start();
		} else {
			throw new RuntimeException("Neither MySQL nor SQLite were defined properly. Aborting plugin load.");
		}
	}
	@SuppressWarnings("resource")
	public static void loadRedis() {
		IVariableRegistry<String> configRegistry = ServiceLocator.getService(ConfigRegistry.class);
		Jedis jedis = null;
		
		if (
			configRegistry.hasRegister("redis.address")
			&& configRegistry.hasRegister("redis.port")
		) {
			jedis = new Jedis(configRegistry.getRegister("redis.address", String.class), configRegistry.getRegister("redis.port", Number.class).intValue());
			jedis.connect();
			if (configRegistry.hasRegister("redis.pass")) {
				jedis.auth(configRegistry.getRegister("redis.pass", String.class));
			}
			ServiceLocator.provideService(jedis);
		}
	}
	
	//private
	
}

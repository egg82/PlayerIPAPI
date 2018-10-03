package me.egg82.ipapi;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.json.simple.JSONObject;

import com.google.common.collect.ImmutableSet;

import me.egg82.ipapi.core.IPData;
import me.egg82.ipapi.core.IPResultEventArgs;
import me.egg82.ipapi.core.UUIDData;
import me.egg82.ipapi.core.UUIDResultEventArgs;
import me.egg82.ipapi.debug.IDebugPrinter;
import me.egg82.ipapi.registries.IPToPlayerRegistry;
import me.egg82.ipapi.registries.PlayerToIPRegistry;
import me.egg82.ipapi.sql.mysql.SelectIPResultMySQLCommand;
import me.egg82.ipapi.sql.mysql.SelectUUIDResultMySQLCommand;
import me.egg82.ipapi.sql.sqlite.SelectIPResultSQLiteCommand;
import me.egg82.ipapi.sql.sqlite.SelectUUIDResultSQLiteCommand;
import me.egg82.ipapi.utils.RedisUtil;
import me.egg82.ipapi.utils.ValidationUtil;
import ninja.egg82.analytics.exceptions.IExceptionHandler;
import ninja.egg82.enums.BaseSQLType;
import ninja.egg82.patterns.ServiceLocator;
import ninja.egg82.patterns.registries.IExpiringRegistry;
import ninja.egg82.plugin.utils.JSONUtil;
import ninja.egg82.sql.ISQL;
import redis.clients.jedis.Jedis;

public class IPLookupAPI {
    // vars
    private static IPLookupAPI api = new IPLookupAPI();

    // constructor
    public IPLookupAPI() {

    }

    // public
    public static IPLookupAPI getInstance() {
        return api;
    }

    public ImmutableSet<IPData> getIps(UUID playerUuid) {
        return getIps(playerUuid, true);
    }
    @SuppressWarnings("unchecked")
    public ImmutableSet<IPData> getIps(UUID playerUuid, boolean expensive) {
        if (playerUuid == null) {
            throw new IllegalArgumentException("playerUuid cannot be null.");
        }

        if (Config.debug) {
            ServiceLocator.getService(IDebugPrinter.class).printInfo("Getting data for UUID " + playerUuid.toString());
        }

        // Internal cache - use first
        IExpiringRegistry<UUID, Set<IPData>> playerToIpRegistry = ServiceLocator.getService(PlayerToIPRegistry.class);
        Set<IPData> ips = playerToIpRegistry.getRegister(playerUuid);
        if (ips != null) {
            if (Config.debug) {
                ServiceLocator.getService(IDebugPrinter.class).printInfo(playerUuid.toString() + " found in local cache. Result count: " + ips.size());
            }
            return ImmutableSet.copyOf(ips);
        }

        // Redis - use BEFORE SQL
        try (Jedis redis = RedisUtil.getRedis()) {
            if (redis != null) {
                ips = new HashSet<IPData>();

                String uuidKey = "pipapi:uuid:" + playerUuid.toString();
                Set<String> list = redis.smembers(uuidKey);

                // Iterate IPs and grab info
                for (Iterator<String> i = list.iterator(); i.hasNext();) {
                    String ip = i.next();
                    String infoKey = "pipapi:info:" + playerUuid.toString() + ":" + ip;

                    // Validate IP and remove bad data
                    if (!ValidationUtil.isValidIp(ip)) {
                        redis.srem(uuidKey, ip);
                        redis.del(infoKey);
                        i.remove();
                        continue;
                    }

                    // Grab UUID->IP info
                    String jsonData = redis.get(infoKey);
                    if (jsonData == null) {
                        continue;
                    }

                    try {
                        JSONObject infoObject = JSONUtil.parseObject(jsonData);
                        long created = ((Number) infoObject.get("created")).longValue();
                        long updated = ((Number) infoObject.get("updated")).longValue();

                        // Add the data
                        ips.add(new IPData(ip, created, updated));
                    } catch (Exception ex) {
                        redis.del(infoKey);
                        IExceptionHandler handler = ServiceLocator.getService(IExceptionHandler.class);
                        if (handler != null) {
                            handler.sendException(ex);
                        }
                        ex.printStackTrace();
                    }
                }
            }
        }

        if (ips != null && ips.size() > 0) {
            // Redis returned some data. Cache the result
            playerToIpRegistry.setRegister(playerUuid, ips);
            if (Config.debug) {
                ServiceLocator.getService(IDebugPrinter.class).printInfo(playerUuid.toString() + " found in Redis. Result count: " + ips.size());
            }
            return ImmutableSet.copyOf(ips);
        }

        if (!expensive) {
            // Non-expensive call. Return nothing, but don't cache this result
            return ImmutableSet.of();
        }

        // SQL - use as a last resort
        AtomicReference<IPResultEventArgs> retVal = new AtomicReference<IPResultEventArgs>(null);
        CountDownLatch latch = new CountDownLatch(1);

        BiConsumer<Object, IPResultEventArgs> sqlData = (s, e) -> {
            retVal.set(e);
            latch.countDown();

            ISQL sql = ServiceLocator.getService(ISQL.class);
            if (sql.getType() == BaseSQLType.MySQL) {
                SelectIPResultMySQLCommand c = (SelectIPResultMySQLCommand) s;
                c.onData().detatchAll();
            } else if (sql.getType() == BaseSQLType.SQLite) {
                SelectIPResultSQLiteCommand c = (SelectIPResultSQLiteCommand) s;
                c.onData().detatchAll();
            }
        };

        ISQL sql = ServiceLocator.getService(ISQL.class);
        if (sql.getType() == BaseSQLType.MySQL) {
            SelectIPResultMySQLCommand command = new SelectIPResultMySQLCommand(playerUuid);
            command.onData().attach(sqlData);
            command.start();
        } else if (sql.getType() == BaseSQLType.SQLite) {
            SelectIPResultSQLiteCommand command = new SelectIPResultSQLiteCommand(playerUuid);
            command.onData().attach(sqlData);
            command.start();
        }

        try {
            latch.await();
        } catch (Exception ex) {
            IExceptionHandler handler = ServiceLocator.getService(IExceptionHandler.class);
            if (handler != null) {
                handler.sendException(ex);
            }
            ex.printStackTrace();
        }

        if (retVal.get() == null || retVal.get().getUuid() == null || retVal.get().getResults() == null) {
            // Something went wrong. Don't cache this
            return ImmutableSet.of();
        }

        // Set Redis, if available
        try (Jedis redis = RedisUtil.getRedis()) {
            if (redis != null) {
                for (IPData data : retVal.get().getResults()) {
                    String uuidKey = "pipapi:uuid:" + playerUuid.toString();
                    redis.sadd(uuidKey, data.getIp());

                    String ipKey = "pipapi:ip:" + data.getIp();
                    redis.sadd(ipKey, playerUuid.toString());

                    String infoKey = "pipapi:info:" + playerUuid.toString() + ":" + data.getIp();
                    JSONObject infoObject = new JSONObject();
                    infoObject.put("created", Long.valueOf(data.getCreated()));
                    infoObject.put("updated", Long.valueOf(data.getCreated()));
                    redis.set(infoKey, infoObject.toJSONString());
                }
            }
        }

        ips = retVal.get().getResults();
        // Cache the result
        playerToIpRegistry.setRegister(playerUuid, ips);

        if (Config.debug) {
            ServiceLocator.getService(IDebugPrinter.class).printInfo(playerUuid.toString() + " found in SQL. Result count: " + ips.size());
        }
        return ImmutableSet.copyOf(ips);
    }

    public ImmutableSet<UUIDData> getPlayers(String ip) {
        return getPlayers(ip, true);
    }

    @SuppressWarnings("unchecked")
    public ImmutableSet<UUIDData> getPlayers(String ip, boolean expensive) {
        if (ip == null) {
            throw new IllegalArgumentException("ip cannot be null.");
        }
        if (!ValidationUtil.isValidIp(ip)) {
            return ImmutableSet.of();
        }

        if (Config.debug) {
            ServiceLocator.getService(IDebugPrinter.class).printInfo("Getting data for IP " + ip);
        }

        // Internal cache - use first
        IExpiringRegistry<String, Set<UUIDData>> ipToPlayerRegistry = ServiceLocator.getService(IPToPlayerRegistry.class);
        Set<UUIDData> uuids = ipToPlayerRegistry.getRegister(ip);
        if (uuids != null) {
            if (Config.debug) {
                ServiceLocator.getService(IDebugPrinter.class).printInfo(ip + " found in local cache. Result count: " + uuids.size());
            }
            return ImmutableSet.copyOf(uuids);
        }

        // Redis - use BEFORE SQL
        try (Jedis redis = RedisUtil.getRedis()) {
            if (redis != null) {
                uuids = new HashSet<UUIDData>();

                String ipKey = "pipapi:ip:" + ip;
                Set<String> list = redis.smembers(ipKey);
                // Iterate UUIDs and grab info
                for (Iterator<String> i = list.iterator(); i.hasNext();) {
                    String uuid = i.next();
                    String infoKey = "pipapi:info:" + uuid + ":" + ip;

                    // Validate UUID and remove bad data
                    if (!ValidationUtil.isValidUuid(uuid)) {
                        redis.srem(ipKey, uuid);
                        redis.del(infoKey);
                        i.remove();
                        continue;
                    }

                    // Grab UUID->IP info
                    String jsonData = redis.get(infoKey);
                    if (jsonData == null) {
                        continue;
                    }

                    // Parse info into more useful object types
                    try {
                        JSONObject infoObject = JSONUtil.parseObject(jsonData);
                        long created = ((Number) infoObject.get("created")).longValue();
                        long updated = ((Number) infoObject.get("updated")).longValue();

                        // Add the data
                        uuids.add(new UUIDData(UUID.fromString(uuid), created, updated));
                    } catch (Exception ex) {
                        redis.del(infoKey);
                        IExceptionHandler handler = ServiceLocator.getService(IExceptionHandler.class);
                        if (handler != null) {
                            handler.sendException(ex);
                        }
                        ex.printStackTrace();
                    }
                }
            }
        }

        if (uuids != null && uuids.size() > 0) {
            // Redis returned some data. Cache the result
            ipToPlayerRegistry.setRegister(ip, uuids);
            if (Config.debug) {
                ServiceLocator.getService(IDebugPrinter.class).printInfo(ip.toString() + " found in Redis. Result count: " + uuids.size());
            }
            return ImmutableSet.copyOf(uuids);
        }

        if (!expensive) {
            // Non-expensive call. Return nothing, but don't cache this result
            return ImmutableSet.of();
        }

        // SQL - use as a last resort
        AtomicReference<UUIDResultEventArgs> retVal = new AtomicReference<UUIDResultEventArgs>(null);
        CountDownLatch latch = new CountDownLatch(1);

        BiConsumer<Object, UUIDResultEventArgs> sqlData = (s, e) -> {
            retVal.set(e);
            latch.countDown();

            ISQL sql = ServiceLocator.getService(ISQL.class);
            if (sql.getType() == BaseSQLType.MySQL) {
                SelectUUIDResultMySQLCommand c = (SelectUUIDResultMySQLCommand) s;
                c.onData().detatchAll();
            } else if (sql.getType() == BaseSQLType.SQLite) {
                SelectUUIDResultSQLiteCommand c = (SelectUUIDResultSQLiteCommand) s;
                c.onData().detatchAll();
            }
        };

        ISQL sql = ServiceLocator.getService(ISQL.class);
        if (sql.getType() == BaseSQLType.MySQL) {
            SelectUUIDResultMySQLCommand command = new SelectUUIDResultMySQLCommand(ip);
            command.onData().attach(sqlData);
            command.start();
        } else if (sql.getType() == BaseSQLType.SQLite) {
            SelectUUIDResultSQLiteCommand command = new SelectUUIDResultSQLiteCommand(ip);
            command.onData().attach(sqlData);
            command.start();
        }

        try {
            latch.await();
        } catch (Exception ex) {
            IExceptionHandler handler = ServiceLocator.getService(IExceptionHandler.class);
            if (handler != null) {
                handler.sendException(ex);
            }
            ex.printStackTrace();
        }

        if (retVal.get() == null || retVal.get().getIp() == null || retVal.get().getResults() == null) {
            // Something went wrong. Don't cache this
            return ImmutableSet.of();
        }

        // Set Redis, if available
        try (Jedis redis = RedisUtil.getRedis()) {
            if (redis != null) {
                for (UUIDData data : retVal.get().getResults()) {
                    String uuidKey = "pipapi:uuid:" + data.getUuid().toString();
                    redis.sadd(uuidKey, ip);

                    String ipKey = "pipapi:ip:" + ip;
                    redis.sadd(ipKey, data.getUuid().toString());

                    String infoKey = "pipapi:info:" + data.getUuid().toString() + ":" + ip;
                    JSONObject infoObject = new JSONObject();
                    infoObject.put("created", Long.valueOf(data.getCreated()));
                    infoObject.put("updated", Long.valueOf(data.getCreated()));
                    redis.set(infoKey, infoObject.toJSONString());
                }
            }
        }

        uuids = retVal.get().getResults();
        // Cache the result
        ipToPlayerRegistry.setRegister(ip, uuids);

        if (Config.debug) {
            ServiceLocator.getService(IDebugPrinter.class).printInfo(ip + " found in SQL. Result count: " + uuids.size());
        }
        return ImmutableSet.copyOf(uuids);
    }

    // private

}

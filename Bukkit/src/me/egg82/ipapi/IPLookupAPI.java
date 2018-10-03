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
import me.egg82.ipapi.core.IPEventArgs;
import me.egg82.ipapi.core.UUIDData;
import me.egg82.ipapi.core.UUIDEventArgs;
import me.egg82.ipapi.registries.IPToPlayerRegistry;
import me.egg82.ipapi.registries.PlayerToIPRegistry;
import me.egg82.ipapi.sql.mysql.SelectIPsMySQLCommand;
import me.egg82.ipapi.sql.mysql.SelectUUIDsMySQLCommand;
import me.egg82.ipapi.sql.sqlite.SelectIPsSQLiteCommand;
import me.egg82.ipapi.sql.sqlite.SelectUUIDsSQLiteCommand;
import me.egg82.ipapi.utils.RedisUtil;
import me.egg82.ipapi.utils.ValidationUtil;
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

        // Internal cache - use first
        IExpiringRegistry<UUID, Set<IPData>> playerToIpRegistry = ServiceLocator.getService(PlayerToIPRegistry.class);
        Set<IPData> ips = playerToIpRegistry.getRegister(playerUuid);
        if (ips != null) {
            return ImmutableSet.copyOf(ips);
        }

        // Redis - use INSTEAD of SQL
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
                        ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
                        ex.printStackTrace();
                    }
                }
            }
        }

        if (ips != null && ips.size() > 0) {
            // Redis returned some data. Cache the result
            playerToIpRegistry.setRegister(playerUuid, ips);
            return ImmutableSet.copyOf(ips);
        }

        if (!expensive) {
            // Non-expensive call. Return nothing, but don't cache this result
            return ImmutableSet.of();
        }

        // SQL - use as a last resort
        AtomicReference<Set<IPData>> retVal = new AtomicReference<Set<IPData>>(null);
        CountDownLatch latch = new CountDownLatch(1);

        BiConsumer<Object, IPEventArgs> sqlData = (s, e) -> {
            retVal.set(e.getIpData());

            ISQL sql = ServiceLocator.getService(ISQL.class);
            if (sql.getType() == BaseSQLType.MySQL) {
                SelectIPsMySQLCommand c = (SelectIPsMySQLCommand) s;
                c.onData().detatchAll();
            } else if (sql.getType() == BaseSQLType.SQLite) {
                SelectIPsSQLiteCommand c = (SelectIPsSQLiteCommand) s;
                c.onData().detatchAll();
            }

            latch.countDown();
        };

        ISQL sql = ServiceLocator.getService(ISQL.class);
        if (sql.getType() == BaseSQLType.MySQL) {
            SelectIPsMySQLCommand command = new SelectIPsMySQLCommand(playerUuid);
            command.onData().attach(sqlData);
            command.start();
        } else if (sql.getType() == BaseSQLType.SQLite) {
            SelectIPsSQLiteCommand command = new SelectIPsSQLiteCommand(playerUuid);
            command.onData().attach(sqlData);
            command.start();
        }

        try {
            latch.await();
        } catch (Exception ex) {
            ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
            ex.printStackTrace();
        }

        if (retVal.get() == null) {
            // Something went wrong. Don't cache this
            return ImmutableSet.of();
        }

        // Set Redis, if available
        try (Jedis redis = RedisUtil.getRedis()) {
            if (redis != null) {
                for (IPData data : retVal.get()) {
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

        ips = retVal.get();
        // Cache the result
        playerToIpRegistry.setRegister(playerUuid, ips);

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

        // Internal cache - use first
        IExpiringRegistry<String, Set<UUIDData>> ipToPlayerRegistry = ServiceLocator.getService(IPToPlayerRegistry.class);
        Set<UUIDData> uuids = ipToPlayerRegistry.getRegister(ip);
        if (uuids != null) {
            return ImmutableSet.copyOf(uuids);
        }

        // Redis - use INSTEAD of SQL
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
                        ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
                        ex.printStackTrace();
                    }
                }
            }
        }

        if (uuids != null && uuids.size() > 0) {
            // Redis returned some data. Cache the result
            ipToPlayerRegistry.setRegister(ip, uuids);
            return ImmutableSet.copyOf(uuids);
        }

        if (!expensive) {
            // Non-expensive call. Return nothing, but don't cache this result
            return ImmutableSet.of();
        }

        // SQL - use as a last resort
        AtomicReference<Set<UUIDData>> retVal = new AtomicReference<Set<UUIDData>>(null);
        CountDownLatch latch = new CountDownLatch(1);

        BiConsumer<Object, UUIDEventArgs> sqlData = (s, e) -> {
            retVal.set(e.getUuidData());

            ISQL sql = ServiceLocator.getService(ISQL.class);
            if (sql.getType() == BaseSQLType.MySQL) {
                SelectUUIDsMySQLCommand c = (SelectUUIDsMySQLCommand) s;
                c.onData().detatchAll();
            } else if (sql.getType() == BaseSQLType.SQLite) {
                SelectUUIDsSQLiteCommand c = (SelectUUIDsSQLiteCommand) s;
                c.onData().detatchAll();
            }

            latch.countDown();
        };

        ISQL sql = ServiceLocator.getService(ISQL.class);
        if (sql.getType() == BaseSQLType.MySQL) {
            SelectUUIDsMySQLCommand command = new SelectUUIDsMySQLCommand(ip);
            command.onData().attach(sqlData);
            command.start();
        } else if (sql.getType() == BaseSQLType.SQLite) {
            SelectUUIDsSQLiteCommand command = new SelectUUIDsSQLiteCommand(ip);
            command.onData().attach(sqlData);
            command.start();
        }

        try {
            latch.await();
        } catch (Exception ex) {
            ServiceLocator.getService(IExceptionHandler.class).silentException(ex);
            ex.printStackTrace();
        }

        if (retVal.get() == null) {
            // Something went wrong. Don't cache this
            return ImmutableSet.of();
        }

        // Set Redis, if available
        try (Jedis redis = RedisUtil.getRedis()) {
            if (redis != null) {
                for (UUIDData data : retVal.get()) {
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

        uuids = retVal.get();
        // Cache the result
        ipToPlayerRegistry.setRegister(ip, uuids);

        return ImmutableSet.copyOf(uuids);
    }

    // private

}

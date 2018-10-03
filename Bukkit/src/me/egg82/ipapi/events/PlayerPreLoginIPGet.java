package me.egg82.ipapi.events;

import java.net.InetAddress;
import java.util.UUID;

import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import me.egg82.ipapi.utils.PlayerCacheUtil;
import ninja.egg82.plugin.handlers.events.LowEventHandler;

public class PlayerPreLoginIPGet extends LowEventHandler<AsyncPlayerPreLoginEvent> {
    // vars

    // constructor
    public PlayerPreLoginIPGet() {
        super();
    }

    // public

    // private
    protected void onExecute(long elapsedMilliseconds) {
        UUID uuid = event.getUniqueId();
        String ip = getIp(event.getAddress());

        if (ip == null || ip.isEmpty()) {
            return;
        }

        PlayerCacheUtil.addInfo(uuid, ip);
    }

    private String getIp(InetAddress address) {
        if (address == null) {
            return null;
        }
        return address.getHostAddress();
    }
}

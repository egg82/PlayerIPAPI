# PlayerIPAPI
Bukkit/Spigot API that logs player/IP combos and allows for a nice developer API to easily retrieve that data

# Commands
ipapireload - Reloads the plugin

# Permissions
ipapi.admin - allows access to the ipapireload command

# Maven
    <repository>
      <id>egg82-ninja</id>
      <url>https://www.myget.org/F/egg82-java/maven/</url>
    </repository>

### Latest Repo
https://www.myget.org/feed/egg82-java/package/maven/ninja.egg82.plugins/PlayerIPAPI

### API usage
    IPLookupAPI.getInstance();
    ...
    Set<String> getIps(UUID playerUuid);
    Set<UUID> getPlayers(String ip);

### Example - list all players logged into all IPs that a specified player has ever logged in on
    IPLookupAPI api = IPLookupAPI.getInstance();
    Set<String> ips = api.getIps(event.getPlayer().getUniqueId());
    for (String ip : ips) {
        Set<UUID> players = api.getPlayers(ip);
        for (UUID uuid : players) {
            // Do something with player UUID
        }
    }
### Example - emulate Essentials /seen <IP>
    IPLookupAPI api = IPLookupAPI.getInstance();
    Set<UUID> uuids = api.getPlayers(args[0]);
    for (UUID uuid : uuids) {
        // Do something with player UUID
    }
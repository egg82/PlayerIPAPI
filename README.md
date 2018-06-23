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
    ImmutableSet<IPData> getIps(UUID playerUuid);
    ImmutableSet<UUIDData> getPlayers(String ip);
	...
	IPData ipData = ips.get(0);
	ipData.getIp();
	ipData.getCreated();
	ipData.getUpdated();
	...
	UUIDData uuidData = uuids.get(0);
	uuidData.getUuid();
	uuidData.getCreated();
	uuidData.getUpdated();

### Example - list all players logged into all IPs that a specified player has ever logged in on
    IPLookupAPI api = IPLookupAPI.getInstance();
    ImmutableSet<IPData> ips = api.getIps(event.getPlayer().getUniqueId());
    for (IPData ip : ips) {
        ImmutableSet<UUIDData> players = api.getPlayers(ip.getIp());
        for (UUIDData uuid : players) {
            // Do something with uuid.getUuid()
        }
    }
### Example - emulate Essentials /seen <IP>
    IPLookupAPI api = IPLookupAPI.getInstance();
    ImmutableSet<UUIDData> uuids = api.getPlayers(args[0]);
    for (UUIDData uuid : uuids) {
        // Do something with uuid.getUuid()
    }
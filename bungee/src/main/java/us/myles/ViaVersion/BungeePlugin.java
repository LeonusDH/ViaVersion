package us.myles.ViaVersion;

import com.google.gson.JsonObject;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import us.myles.ViaVersion.api.Pair;
import us.myles.ViaVersion.api.Via;
import us.myles.ViaVersion.api.ViaAPI;
import us.myles.ViaVersion.api.command.ViaCommandSender;
import us.myles.ViaVersion.api.configuration.ConfigurationProvider;
import us.myles.ViaVersion.api.data.UserConnection;
import us.myles.ViaVersion.api.platform.TaskId;
import us.myles.ViaVersion.api.platform.ViaPlatform;
import us.myles.ViaVersion.api.protocol.Protocol;
import us.myles.ViaVersion.api.protocol.ProtocolRegistry;
import us.myles.ViaVersion.bungee.commands.BungeeCommand;
import us.myles.ViaVersion.bungee.commands.BungeeCommandHandler;
import us.myles.ViaVersion.bungee.commands.BungeeCommandSender;
import us.myles.ViaVersion.bungee.platform.*;
import us.myles.ViaVersion.bungee.service.ProtocolDetectorService;
import us.myles.ViaVersion.bungee.storage.BungeeStorage;
import us.myles.ViaVersion.dump.PluginInfo;
import us.myles.ViaVersion.protocols.base.ProtocolInfo;
import us.myles.ViaVersion.util.GsonUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class BungeePlugin extends Plugin implements ViaPlatform, Listener {
    private static Method getPendingConnection;
    private static Method getHandshake;
    private static Method setProtocol;

    private BungeeViaAPI api;
    private BungeeConfigAPI config;
    private BungeeCommandHandler commandHandler;

    static {
        try {
            getPendingConnection = Class.forName("net.md_5.bungee.UserConnection").getDeclaredMethod("getPendingConnection");
            getHandshake = Class.forName("net.md_5.bungee.connection.InitialHandler").getDeclaredMethod("getHandshake");
            setProtocol = Class.forName("net.md_5.bungee.protocol.packet.Handshake").getDeclaredMethod("setProtocolVersion", int.class);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onLoad() {
        api = new BungeeViaAPI();
        config = new BungeeConfigAPI(getDataFolder());
        commandHandler = new BungeeCommandHandler();
        ProxyServer.getInstance().getPluginManager().registerCommand(this, new BungeeCommand(commandHandler));
        // Init platform
        Via.init(ViaManager.builder()
                .platform(this)
                .injector(new BungeeViaInjector())
                .loader(new BungeeViaLoader(this))
                .commandHandler(commandHandler)
                .build());
    }

    @Override
    public void onEnable() {
        // Inject
        Via.getManager().init();
    }

    @Override
    public String getPlatformName() {
        return "BungeeCord";
    }

    @Override
    public String getPluginVersion() {
        return getDescription().getVersion();
    }

    @Override
    public TaskId runAsync(Runnable runnable) {
        return new BungeeTaskId(getProxy().getScheduler().runAsync(this, runnable).getId());
    }

    @Override
    public TaskId runSync(Runnable runnable) {
        return runAsync(runnable);
    }

    @Override
    public TaskId runRepeatingSync(Runnable runnable, Long ticks) {
        return new BungeeTaskId(getProxy().getScheduler().schedule(this, runnable, 0, ticks * 50, TimeUnit.MILLISECONDS).getId());
    }

    @Override
    public void cancelTask(TaskId taskId) {
        if (taskId == null) return;
        if (taskId.getObject() == null) return;
        if (taskId instanceof BungeeTaskId) {
            getProxy().getScheduler().cancel((Integer) taskId.getObject());
        }
    }

    @Override
    public ViaCommandSender[] getOnlinePlayers() {
        ViaCommandSender[] array = new ViaCommandSender[getProxy().getPlayers().size()];
        int i = 0;
        for (ProxiedPlayer player : getProxy().getPlayers()) {
            array[i++] = new BungeeCommandSender(player);
        }
        return array;
    }

    @Override
    public void sendMessage(UUID uuid, String message) {
        getProxy().getPlayer(uuid).sendMessage(TextComponent.fromLegacyText(message));
    }

    @Override
    public boolean kickPlayer(UUID uuid, String message) {
        if (getProxy().getPlayer(uuid) != null) {
            getProxy().getPlayer(uuid).disconnect(TextComponent.fromLegacyText(message));
            return true;
        }
        return false;
    }

    @Override
    public boolean isPluginEnabled() {
        return true;
    }

    @Override
    public ViaAPI getApi() {
        return api;
    }

    @Override
    public BungeeConfigAPI getConf() {
        return config;
    }

    @Override
    public ConfigurationProvider getConfigurationProvider() {
        return config;
    }

    @Override
    public void onReload() {
        // Injector prints a message <3
    }

    @Override
    public JsonObject getDump() {
        JsonObject platformSpecific = new JsonObject();

        List<PluginInfo> plugins = new ArrayList<>();
        for (Plugin p : ProxyServer.getInstance().getPluginManager().getPlugins())
            plugins.add(new PluginInfo(true, p.getDescription().getName(), p.getDescription().getVersion(), p.getDescription().getMain(), Arrays.asList(p.getDescription().getAuthor())));

        platformSpecific.add("plugins", GsonUtil.getGson().toJsonTree(plugins));
        platformSpecific.add("servers", GsonUtil.getGson().toJsonTree(ProtocolDetectorService.getDetectedIds()));
        return platformSpecific;
    }

    @EventHandler
    public void onQuit(PlayerDisconnectEvent e) {
        Via.getManager().removePortedClient(e.getPlayer().getUniqueId());
    }

    // Set the handshake version every time someone connects to any server
    @EventHandler
    public void onServerConnect(ServerConnectEvent e) throws NoSuchFieldException, IllegalAccessException {
        UserConnection user = Via.getManager().getConnection(e.getPlayer().getUniqueId());
        if (!user.has(BungeeStorage.class)) {
            user.put(new BungeeStorage(user, e.getPlayer()));
        }

        int protocolId = ProtocolDetectorService.getProtocolId(e.getTarget().getName());
        List<Pair<Integer, Protocol>> protocols = ProtocolRegistry.getProtocolPath(user.get(ProtocolInfo.class).getProtocolVersion(), protocolId);

        // Check if ViaVersion can support that version
        try {
            Object pendingConnection = getPendingConnection.invoke(e.getPlayer());
            Object handshake = getHandshake.invoke(pendingConnection);
            setProtocol.invoke(handshake, protocols == null ? user.get(ProtocolInfo.class).getProtocolVersion() : protocolId);
        } catch (InvocationTargetException e1) {
            e1.printStackTrace();
        }
    }
}
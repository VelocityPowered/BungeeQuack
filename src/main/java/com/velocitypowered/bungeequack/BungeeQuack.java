package com.velocitypowered.bungeequack;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.*;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.UuidUtils;
import net.kyori.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

@Plugin(id = "bungeequack", name = "BungeeQuack", version = "1.0-SNAPSHOT",
        description = "Emulates BungeeCord plugin messaging channels on Velocity",
        authors = {"Velocity team"})
public class BungeeQuack {
    private static final LegacyChannelIdentifier LEGACY_BUNGEE_CHANNEL = new LegacyChannelIdentifier("BungeeCord");
    private static final MinecraftChannelIdentifier MODERN_BUNGEE_CHANNEL = MinecraftChannelIdentifier.create("bungeecord", "main");

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public BungeeQuack(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(LEGACY_BUNGEE_CHANNEL, MODERN_BUNGEE_CHANNEL);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(LEGACY_BUNGEE_CHANNEL) && !event.getIdentifier().equals(MODERN_BUNGEE_CHANNEL)) {
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection)) {
            return;
        }

        ServerConnection connection = (ServerConnection) event.getSource();
        ByteArrayDataInput in = event.dataAsDataStream();
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        String subChannel = in.readUTF();

        if (subChannel.equals("ForwardToPlayer")) {
            server.getPlayer(in.readUTF())
                    .flatMap(Player::getCurrentServer)
                    .ifPresent(server -> server.sendPluginMessage(event.getIdentifier(), prepareForwardMessage(in)));
        }
        if (subChannel.equals("Forward")) {
            String target = in.readUTF();
            byte[] toForward = prepareForwardMessage(in);

            if (target.equals("ALL")) {
                for (RegisteredServer rs : server.getAllServers()) {
                    rs.sendPluginMessage(event.getIdentifier(), toForward);
                }
            } else {
                server.getServer(target).ifPresent(conn -> conn.sendPluginMessage(event.getIdentifier(), toForward));
            }
        }
        if (subChannel.equals("Connect")) {
            Optional<RegisteredServer> info = server.getServer(in.readUTF());
            info.ifPresent(serverInfo -> connection.getPlayer().createConnectionRequest(serverInfo).fireAndForget());
        }
        if (subChannel.equals("ConnectOther")) {
            server.getPlayer(in.readUTF()).ifPresent(player -> {
                Optional<RegisteredServer> info = server.getServer(in.readUTF());
                info.ifPresent(serverInfo -> connection.getPlayer().createConnectionRequest(serverInfo).fireAndForget());
            });
        }
        if (subChannel.equals("IP")) {
            out.writeUTF("IP");
            out.writeUTF(connection.getPlayer().getRemoteAddress().getHostString());
            out.writeInt(connection.getPlayer().getRemoteAddress().getPort());
        }
        if (subChannel.equals("PlayerCount")) {
            String target = in.readUTF();
            if (target.equals("ALL")) {
                out.writeUTF("PlayerCount");
                out.writeUTF("ALL");
                out.writeInt(server.getPlayerCount());
            } else {
                server.getServer(target).ifPresent(rs -> {
                    int playersOnServer = rs.getPlayersConnected().size();
                    out.writeUTF("PlayerCount");
                    out.writeUTF(rs.getServerInfo().getName());
                    out.writeInt(playersOnServer);
                });
            }
        }
        if (subChannel.equals("PlayerList")) {
            String target = in.readUTF();
            if (target.equals("ALL")) {
                out.writeUTF("PlayerList");
                out.writeUTF("ALL");
                out.writeUTF(server.getAllPlayers().stream().map(Player::getUsername).collect(Collectors.joining(", ")));
            } else {
                server.getServer(target).ifPresent(info -> {
                    String playersOnServer = info.getPlayersConnected().stream().map(Player::getUsername).collect(Collectors.joining(", "));
                    out.writeUTF("PlayerList");
                    out.writeUTF(info.getServerInfo().getName());
                    out.writeUTF(playersOnServer);
                });
            }
        }
        if (subChannel.equals("GetServers")) {
            out.writeUTF("GetServers");
            out.writeUTF(server.getAllServers().stream().map(s -> s.getServerInfo().getName()).collect(Collectors.joining(", ")));
        }
        if (subChannel.equals("Message")) {
            String target = in.readUTF();
            String message = in.readUTF();
            if (target.equals("ALL")) {
                for (Player player : server.getAllPlayers()) {
                    player.sendMessage(LegacyComponentSerializer.INSTANCE.deserialize(message));
                }
            } else {
                server.getPlayer(target).ifPresent(player -> {
                    player.sendMessage(LegacyComponentSerializer.INSTANCE.deserialize(message));
                });
            }
        }
        if (subChannel.equals("GetServer")) {
            out.writeUTF("GetServer");
            out.writeUTF(connection.getServerInfo().getName());
        }
        if (subChannel.equals("UUID")) {
            out.writeUTF("UUID");
            out.writeUTF(UuidUtils.toUndashed(connection.getPlayer().getUniqueId()));
        }
        if (subChannel.equals("UUIDOther")) {
            server.getPlayer(in.readUTF()).ifPresent(player -> {
                out.writeUTF("UUIDOther");
                out.writeUTF(player.getUsername());
                out.writeUTF(UuidUtils.toUndashed(player.getUniqueId()));
            });
        }
        if (subChannel.equals("ServerIP")) {
            server.getServer(in.readUTF()).ifPresent(info -> {
                out.writeUTF("ServerIP");
                out.writeUTF(info.getServerInfo().getName());
                out.writeUTF(info.getServerInfo().getAddress().getAddress().getHostAddress());
                out.writeShort(info.getServerInfo().getAddress().getPort());
            });
        }
        if (subChannel.equals("KickPlayer")) {
            server.getPlayer(in.readUTF()).ifPresent(player -> {
                String kickReason = in.readUTF();
                player.disconnect(LegacyComponentSerializer.INSTANCE.deserialize(kickReason));
            });
        }

        // If we wrote data, reply back on the BungeeCord channel
        byte[] data = out.toByteArray();
        if (data.length > 0) {
            connection.sendPluginMessage(event.getIdentifier(), data);
        }
    }

    private byte[] prepareForwardMessage(ByteArrayDataInput in) {
        String channel = in.readUTF();
        short messageLength = in.readShort();
        byte[] message = new byte[messageLength];
        in.readFully(message);

        ByteArrayDataOutput forwarded = ByteStreams.newDataOutput();
        forwarded.writeUTF(channel);
        forwarded.writeShort(messageLength);
        forwarded.write(message);
        return forwarded.toByteArray();
    }
}

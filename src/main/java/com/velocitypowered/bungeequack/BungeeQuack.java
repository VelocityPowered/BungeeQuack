package com.velocitypowered.bungeequack;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.*;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.text.serializer.ComponentSerializers;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

@Plugin(id = "bungeequack", name = "BungeeQuack", version = "1.0-SNAPSHOT",
        description = "Emulates BungeeCord plugin messaging channels on Velocity",
        authors = {"Velocity team"})
public class BungeeQuack implements MessageHandler {
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
        server.getChannelRegistrar().register(this, LEGACY_BUNGEE_CHANNEL, MODERN_BUNGEE_CHANNEL);
    }

    @Override
    public ForwardStatus handle(ChannelMessageSource source, ChannelSide channelSide, ChannelIdentifier identifier, byte[] bytes) {
        if (channelSide == ChannelSide.FROM_CLIENT) {
            return ForwardStatus.HANDLED;
        }

        ServerConnection connection = (ServerConnection) source;
        ByteArrayDataInput in = ByteStreams.newDataInput(bytes);
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        String subChannel = in.readUTF();

        if (subChannel.equals("ForwardToPlayer")) {
            server.getPlayer(in.readUTF())
                    .flatMap(Player::getCurrentServer)
                    .ifPresent(server -> {
                        server.sendPluginMessage(identifier, prepareForwardMessage(in));
                    });
        }
        if (subChannel.equals("Forward")) {
            String target = in.readUTF();
            byte[] toForward = prepareForwardMessage(in);

            if (target.equals("ALL")) {
                for (ServerConnection conn : findConnectionsToServers()) {
                    conn.sendPluginMessage(identifier, toForward);
                }
            } else {
                findSomeConnection(target).ifPresent(conn -> conn.sendPluginMessage(identifier, toForward));
            }
        }
        if (subChannel.equals("Connect")) {
            Optional<ServerInfo> info = server.getServerInfo(in.readUTF());
            info.ifPresent(serverInfo -> connection.getPlayer().createConnectionRequest(serverInfo).fireAndForget());
        }
        if (subChannel.equals("ConnectOther")) {
            server.getPlayer(in.readUTF()).ifPresent(player -> {
                Optional<ServerInfo> info = server.getServerInfo(in.readUTF());
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
            out.writeUTF("PlayerCount");
            if (target.equals("ALL")) {
                out.writeUTF("ALL");
                out.writeInt(server.getPlayerCount());
            } else {
                server.getServerInfo(target).ifPresent(info -> {
                    int playersOnServer = 0;
                    for (Player player : server.getAllPlayers()) {
                        if (player.getCurrentServer().filter(s -> s.getServerInfo().equals(info)).isPresent()) {
                            playersOnServer++;
                        }
                    }
                    out.writeUTF(info.getName());
                    out.writeInt(playersOnServer);
                });
            }
        }
        if (subChannel.equals("PlayerList")) {
            String target = in.readUTF();
            out.writeUTF("PlayerList");
            if (target.equals("ALL")) {
                out.writeUTF("ALL");
                out.writeUTF(server.getAllPlayers().stream().map(Player::getUsername).collect(Collectors.joining(",")));
            } else {
                server.getServerInfo(target).ifPresent(info -> {
                    List<String> playersOnServer = new ArrayList<>();
                    for (Player player : server.getAllPlayers()) {
                        if (player.getCurrentServer().filter(s -> s.getServerInfo().equals(info)).isPresent()) {
                            playersOnServer.add(player.getUsername());
                        }
                    }
                    out.writeUTF(info.getName());
                    out.writeUTF(Joiner.on(",").join(playersOnServer));
                });
            }
        }
        if (subChannel.equals("GetServers")) {
            out.writeUTF("GetServers");
            out.writeUTF(server.getAllServers().stream().map(ServerInfo::getName).collect(Collectors.joining(",")));
        }
        if (subChannel.equals("Message")) {
            String target = in.readUTF();
            String message = in.readUTF();
            if (target.equals("ALL")) {
                for (Player player : server.getAllPlayers()) {
                    player.sendMessage(ComponentSerializers.LEGACY.deserialize(message));
                }
            } else {
                server.getPlayer(target).ifPresent(player -> {
                    player.sendMessage(ComponentSerializers.LEGACY.deserialize(message));
                });
            }
        }
        if (subChannel.equals("GetServer")) {
            out.writeUTF("GetServer");
            out.writeUTF(connection.getServerInfo().getName());
        }
        if (subChannel.equals("UUID")) {
            out.writeUTF("UUID");
            out.writeUTF(connection.getPlayer().getUniqueId().toString().replace("-", ""));
        }
        if (subChannel.equals("UUIDOther")) {
            server.getPlayer(in.readUTF()).ifPresent(player -> {
                out.writeUTF("UUIDOther");
                out.writeUTF(player.getUsername());
                out.writeUTF(player.getUniqueId().toString().replace("-", ""));
            });
        }
        if (subChannel.equals("ServerIP")) {
            server.getServerInfo(in.readUTF()).ifPresent(info -> {
                out.writeUTF("ServerIP");
                out.writeUTF(info.getName());
                out.writeUTF(info.getAddress().getAddress().getHostAddress());
                out.writeShort(info.getAddress().getPort());
            });
        }
        if (subChannel.equals("KickPlayer")) {
            server.getPlayer(in.readUTF()).ifPresent(player -> {
                String kickReason = in.readUTF();
                player.disconnect(ComponentSerializers.PLAIN.deserialize(kickReason));
            });
        }

        // If we wrote data, reply back on the BungeeCord channel
        byte[] data = out.toByteArray();
        if (data.length > 0) {
            connection.sendPluginMessage(identifier, data);
        }
        return ForwardStatus.HANDLED;
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

    private Optional<ServerConnection> findSomeConnection(String serverName) {
        return server.getAllPlayers().stream()
                .map(p -> p.getCurrentServer().filter(s -> s.getServerInfo().getName().equalsIgnoreCase(serverName)))
                .filter(Optional::isPresent)
                .findAny()
                .flatMap(o -> o);
    }

    private List<ServerConnection> findConnectionsToServers() {
        Map<ServerInfo, ServerConnection> connections = new HashMap<>();
        for (Player player : server.getAllPlayers()) {
            Optional<ServerConnection> connection = player.getCurrentServer();
            connection.ifPresent(serverConnection -> connections.putIfAbsent(serverConnection.getServerInfo(), serverConnection));
        }
        return ImmutableList.copyOf(connections.values());
    }
}

// MIT License
//
// Copyright (c) 2022 fren_gor
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package com.fren_gor.lightInjector;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.protocol.login.PacketLoginOutSuccess;
import net.minecraft.server.network.ServerConnection;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_19_R1.CraftServer;
import org.bukkit.craftbukkit.v1_19_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * A light yet complete and fast packet injector for Spigot servers.
 * <p>
 * Can listen to every packet since {@link AsyncPlayerPreLoginEvent} fires (approximately since the set compression
 * packet, see <a href="https://wiki.vg/Protocol_FAQ#What.27s_the_normal_login_sequence_for_a_client.3F">What's the normal login sequence for a client?</a>).
 * <p>
 * Do not listen to packets exchanged during status pings (i.e. server list pings).
 * Use the {@link ServerListPingEvent} to change the ping information.
 *
 * @author fren_gor
 */
public abstract class LightInjector {

    // Used to make identifiers unique if multiple instances are created. This doesn't need to be atomic
    // since it is called only from the constructor, which is assured to run on the main thread
    private static int ID = 0;

    private final Plugin plugin;
    private final String identifier; // The identifier used to register the ChannelHandler into the channel pipeline

    // The list of NetworkManagers
    private final List<NetworkManager> networkManagers;

    private final EventListener listener = new EventListener();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Used to allow PacketHandlers to set the PacketHandler.player field
    private final Map<UUID, Player> playerCache = Collections.synchronizedMap(new HashMap<>());
    // Set of already injected channels, used to speed up channel injection during AsyncPlayerPreLoginEvent
    private final Set<Channel> injectedChannels = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * Initializes the injector and starts to listen to packets.
     * <p>
     * Note that it is possible to create more than one instance per plugin.
     *
     * @param plugin The {@link Plugin} which is instantiating this injector.
     * @throws NullPointerException When the provided {@code plugin} is {@code null}.
     * @throws IllegalStateException When <b>not</b> called from the main thread.
     */
    public LightInjector(@NotNull Plugin plugin) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("LightInjector must be constructed on the main thread.");
        }

        this.plugin = Objects.requireNonNull(plugin, "Plugin is null.");
        this.identifier = Objects.requireNonNull(getIdentifier(), "getIdentifier() returned a null value.") + '-' + ID++;

        final ServerConnection conn = ((CraftServer) Bukkit.getServer()).getServer().ad();

        if (conn == null) {
            throw new RuntimeException("This shouldn't have happened."); // Should never happen
        }

        this.networkManagers = conn.e();

        Bukkit.getPluginManager().registerEvents(listener, plugin);

        // Inject already online players
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                injectPlayer(p);
            } catch (Exception exception) {
                plugin.getLogger().log(Level.SEVERE, "An error occurred while injecting a player:", exception);
            }
        }
    }

    /**
     * Called asynchronously (i.e. not from main thread) when a packet is received from a {@link Player}.
     *
     * @param sender The {@link Player} which sent the packet. May be {@code null} for early packets.
     * @param channel The {@link Channel} of the player's connection.
     * @param packet The packet received from player.
     * @return The packet to receive instead, or {@code null} if the packet should be cancelled.
     */
    protected abstract @Nullable Object onPacketReceiveAsync(@Nullable Player sender, @NotNull Channel channel, @NotNull Object packet);

    /**
     * Called asynchronously (i.e. not from main thread) when a packet is sent to a {@link Player}.
     *
     * @param receiver The {@link Player} which will receive the packet. May be {@code null} for early packets.
     * @param channel The {@link Channel} of the player's connection.
     * @param packet The packet to send to the player.
     * @return The packet to send instead, or {@code null} if the packet should be cancelled.
     */
    protected abstract @Nullable Object onPacketSendAsync(@Nullable Player receiver, @NotNull Channel channel, @NotNull Object packet);

    /**
     * Sends a packet to a player. Since the packet will be sent without any special treatment, this will invoke
     * {@link #onPacketSendAsync(Player, Channel, Object) onPacketSendAsync} when the packet will be intercepted by the
     * injector (any other packet injectors present on the sever will intercept and possibly cancel the packet as well).
     *
     * @param receiver The {@link Player} to which the packet will be sent.
     * @param packet The packet to send.
     * @throws NullPointerException When a parameter is {@code null}.
     */
    public final void sendPacket(@NotNull Player receiver, @NotNull Object packet) {
        Objects.requireNonNull(receiver, "Player is null.");
        Objects.requireNonNull(packet, "Packet is null.");
        sendPacket(getChannel(receiver), packet);
    }

    /**
     * Sends a packet over a {@link Channel}. Since the packet will be sent without any special treatment, this will invoke
     * {@link #onPacketSendAsync(Player, Channel, Object) onPacketSendAsync} when the packet will be intercepted by the
     * injector (any other packet injectors present on the sever will intercept and possibly cancel the packet as well).
     *
     * @param channel The {@link Channel} on which the packet will be sent.
     * @param packet The packet to send.
     * @throws NullPointerException When a parameter is {@code null}.
     */
    public final void sendPacket(@NotNull Channel channel, @NotNull Object packet) {
        Objects.requireNonNull(channel, "Channel is null.");
        Objects.requireNonNull(packet, "Packet is null.");
        channel.pipeline().writeAndFlush(packet);
    }

    /**
     * Acts like if the server has received a packet from a player. Since this process is done without any special
     * treatment of the packet, this will invoke {@link #onPacketReceiveAsync(Player, Channel, Object) onPacketReceiveAsync}
     * when the packet will be intercepted by the injector (any other packet injectors present on the sever will intercept
     * and possibly cancel the packet as well).
     *
     * @param sender The {@link Player} from which the packet will be received.
     * @param packet The packet to receive.
     * @throws NullPointerException When a parameter is {@code null}.
     */
    public final void receivePacket(@NotNull Player sender, @NotNull Object packet) {
        Objects.requireNonNull(sender, "Player is null.");
        Objects.requireNonNull(packet, "Packet is null.");
        receivePacket(getChannel(sender), packet);
    }

    /**
     * Acts like if the server has received a packet over a {@link Channel}. Since this process is done without any
     * special treatment of the packet, this will invoke {@link #onPacketReceiveAsync(Player, Channel, Object) onPacketReceiveAsync}
     * when the packet will be intercepted by the injector (any other packet injectors present on the sever will intercept
     * and possibly cancel the packet as well).
     *
     * @param channel The {@link Channel} on which the packet will be received.
     * @param packet The packet to receive.
     * @throws NullPointerException When a parameter is {@code null} or the provided channel is not a player's channel.
     */
    public final void receivePacket(@NotNull Channel channel, @NotNull Object packet) {
        Objects.requireNonNull(channel, "Channel is null.");
        Objects.requireNonNull(packet, "Packet is null.");
        // Fire channel read after encoder in order to run (possible) other injectors code
        ChannelHandlerContext encoder = channel.pipeline().context("encoder");
        Objects.requireNonNull(encoder, "Channel is not a player channel").fireChannelRead(packet);
    }

    /**
     * Gets the unique non-null identifier of this injector. A slightly modified version of the returned identifier will
     * be used to register the {@link ChannelHandler} during injection.
     * <p>
     * This method is only called once per instance and should always return the same {@link String} when two instances
     * are constructed by the same {@link Plugin}.
     * <p>
     * The default implementation returns {@code "light-injector-" + getPlugin().getName()}.
     *
     * @return The unique non-null identifier of this injector.
     */
    protected @NotNull String getIdentifier() {
        return "light-injector-" + plugin.getName();
    }

    /**
     * Closes the injector and uninject every injected player. This method is automatically called when the plugin which
     * instantiated this injector disables, so it is usually unnecessary to invoke it directly.
     * <p>
     * The uninjection may require some time, so {@link #onPacketReceiveAsync(Player, Channel, Object) onPacketReceiveAsync}
     * and {@link #onPacketSendAsync(Player, Channel, Object) onPacketSendAsync} might still be called after this method returns.
     * <p>
     * If this injector is already closed then invoking of this method has no effect.
     */
    public final void close() {
        if (closed.getAndSet(true)) {
            return;
        }

        listener.unregister();

        synchronized (networkManagers) { // Lock out Minecraft
            for (NetworkManager manager : networkManagers) {
                try {
                    Channel channel = getChannel(manager);

                    // Run on event loop to avoid a possible data race with injection in injectPlayer()
                    channel.eventLoop().submit(() -> channel.pipeline().remove(identifier));
                } catch (Exception exception) {
                    plugin.getLogger().log(Level.SEVERE, "An error occurred while uninjecting a player:", exception);
                }
            }
        }

        playerCache.clear();
        injectedChannels.clear();
    }

    /**
     * Returns whether this injector has been closed.
     *
     * @return Whether this injector has been closed.
     * @see #close()
     */
    public final boolean isClosed() {
        return closed.get();
    }

    /**
     * Return the plugin which instantiated this injector.
     *
     * @return The plugin which instantiated this injector.
     * @see #LightInjector(Plugin)
     */
    public final @NotNull Plugin getPlugin() {
        return plugin;
    }

    private void injectPlayer(Player player) {
        injectChannel(getChannel(player)).player = player;
    }

    private PacketHandler injectChannel(Channel channel) {
        PacketHandler handler = new PacketHandler();

        // Run on event loop to avoid a possible data race with uninjection in close()
        channel.eventLoop().submit(() -> {
            if (isClosed()) return; // Don't inject if uninjection has already occurred in close()

            if (injectedChannels.add(channel)) { // Only inject if not already injected
                try {
                    channel.pipeline().addBefore("packet_handler", identifier, handler);
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().severe("Couldn't inject a player, an handler with identifier '" + identifier + "' is already present");
                }
            }
        });

        return handler;
    }

    private NetworkManager getNetworkManager(Player player) {
        return ((CraftPlayer) player).getHandle().b.b;
    }

    private Channel getChannel(Player player) {
        return getChannel(getNetworkManager(player));
    }

    private Channel getChannel(NetworkManager manager) {
        return manager.m;
    }

    // Don't implement Listener for LightInjector in order to hide the event listener from the public API interface
    private final class EventListener implements Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        private void onAsyncPlayerPreLoginEvent(AsyncPlayerPreLoginEvent event) {
            if (isClosed()) {
                return;
            }

            // Inject all not injected managers.
            // This O(n) operation is used to avoid registering a permanent object inside server's ServerSocketChannel
            synchronized (networkManagers) { // Lock out Minecraft
                if (networkManagers instanceof RandomAccess) {
                    // Faster for loop
                    // Iterating backwards is better since new NetworkManagers should be added at the end of the list
                    for (int i = networkManagers.size() - 1; i >= 0; i--) {
                        NetworkManager manager = networkManagers.get(i);
                        injectNetworkManager(manager);
                    }
                } else {
                    // Using standard foreach to avoid any potential performance issues
                    // (networkManagers should be an ArrayList, but we cannot be sure about that due to forks)
                    for (NetworkManager manager : networkManagers) {
                        injectNetworkManager(manager);
                    }
                }
            }
        }

        private void injectNetworkManager(NetworkManager manager) {
            Channel channel = getChannel(manager);
            // This check avoids useless injections
            if (!injectedChannels.contains(channel)) {
                injectChannel(channel);
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        private void onPlayerLoginEvent(PlayerLoginEvent event) {
            if (isClosed()) {
                return;
            }

            // Save Player object for later
            playerCache.put(event.getPlayer().getUniqueId(), event.getPlayer());
        }

        @EventHandler(priority = EventPriority.LOWEST)
        private void onPlayerJoinEvent(PlayerJoinEvent event) {
            if (isClosed()) {
                return;
            }
            Player player = event.getPlayer();

            // At worst, if player hasn't successfully been injected in the previous steps, it's injected now.
            // At this point the Player's PlayerConnection field should have been initialized to a non-null value
            NetworkManager manager = getNetworkManager(player);
            Channel channel = getChannel(manager);
            @Nullable ChannelHandler channelHandler = channel.pipeline().get(identifier);
            if (channelHandler != null) {
                // A channel handler named identifier has been found
                if (channelHandler instanceof PacketHandler) {
                    // The player have already been injected, only set the player as a backup in the eventuality
                    // that anything else failed to set it previously.
                    ((PacketHandler) channelHandler).player = player;

                    // Clear the cache to avoid any possible (but very unlikely to happen) memory leak
                    playerCache.remove(player.getUniqueId());
                }
                return; // Don't inject again
            }

            plugin.getLogger().info("Late injection for player " + player.getName());
            injectChannel(channel).player = player;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        private void onPluginDisableEvent(PluginDisableEvent event) {
            if (plugin.equals(event.getPlugin())) {
                close();
            }
        }

        private void unregister() {
            AsyncPlayerPreLoginEvent.getHandlerList().unregister(this);
            PlayerLoginEvent.getHandlerList().unregister(this);
            PlayerJoinEvent.getHandlerList().unregister(this);
            PluginDisableEvent.getHandlerList().unregister(this);
        }
    }

    private final class PacketHandler extends ChannelDuplexHandler {
        private volatile Player player;

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            // Called during player disconnection

            // Clean data structures
            injectedChannels.remove(ctx.channel());

            super.channelUnregistered(ctx);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) throws Exception {
            if (player == null && packet instanceof PacketLoginOutSuccess) {
                // Player object should be in cache. If it's not, then it'll be PlayerJoinEvent to set the player
                @Nullable Player player = playerCache.remove(((PacketLoginOutSuccess) packet).b().getId());

                // Set the player only if it was contained into the cache
                if (player != null) {
                    this.player = player;
                }
            }

            @Nullable Object newPacket;
            try {
                newPacket = onPacketSendAsync(player, ctx.channel(), packet);
            } catch (OutOfMemoryError error) {
                // Out of memory, re-throw and return immediately
                throw error;
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.SEVERE, "An error occurred while calling onPacketSendAsync:", throwable);
                throwable.printStackTrace();
                super.write(ctx, packet, promise);
                return;
            }
            if (newPacket != null)
                super.write(ctx, newPacket, promise);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object packet) throws Exception {
            @Nullable Object newPacket;
            try {
                newPacket = onPacketReceiveAsync(player, ctx.channel(), packet);
            } catch (OutOfMemoryError error) {
                // Out of memory, re-throw and return immediately
                throw error;
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.SEVERE, "An error occurred while calling onPacketReceiveAsync:", throwable);
                throwable.printStackTrace();
                super.channelRead(ctx, packet);
                return;
            }
            if (newPacket != null)
                super.channelRead(ctx, newPacket);
        }
    }
}

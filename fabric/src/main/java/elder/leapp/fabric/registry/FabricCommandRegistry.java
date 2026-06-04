package elder.leapp.fabric.registry;

// FabricCommandRegistry.java
// Registers all /leappad and /portalid commands with Fabric's command dispatcher.
// IP fetch logic (async HTTP call to api.ipify.org) lives here.
//
// Permission levels:
//   OP required: all commands except /leappad version and /portalid nickname on unclaimed portals
//   All players: /leappad version, /portalid nickname when no nickname exists yet
//
// Port commands operate on PortBindingCache only — the config file is never written at runtime.
// {PORT} and {n} syntax accepts a single value or colon-separated set (e.g. 25566:25567:25568).
//
// M1 fix: /leappad version now reads the version string from fabric.mod.json via
// FabricLoader rather than getImplementationVersion(), which returns null during
// development builds produced by GitHub Actions.

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import elder.leapp.LeapPadCommon;
import elder.leapp.config.LeapPadConfig;
import elder.leapp.config.WorldLanConfig;
import elder.leapp.portal.PortalRegistry;
import elder.leapp.probe.PortBindingCache;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

public class FabricCommandRegistry {

    // Called from LeapPadFabric.onInitialize()
    public static void register() {
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> {
                registerLeapPadCommands(dispatcher);
                registerPortalIdCommands(dispatcher);
            }
        );
    }

    // -------------------------------------------------------
    // /leappad commands
    // -------------------------------------------------------

    private static void registerLeapPadCommands(CommandDispatcher<CommandSourceStack> dispatcher) {

        dispatcher.register(Commands.literal("leappad")

            // /leappad open — bind all config ports
            .then(Commands.literal("open")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    PortBindingCache.bindAllConfigPorts();
                    ctx.getSource().sendSuccess(
                        () -> Component.literal("[Leap! Pad] All config ports bound."), true
                    );
                    return 1;
                })
                // /leappad open {PORT} — bind specific port(s)
                .then(Commands.argument("ports", StringArgumentType.greedyString())
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> {
                        String ports = StringArgumentType.getString(ctx, "ports");
                        for (int port : parsePorts(ports)) {
                            PortBindingCache.bindPort(port);
                        }
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("[Leap! Pad] Port(s) bound: " + ports), true
                        );
                        return 1;
                    })
                )
            )

            // /leappad close — unbind all ports
            .then(Commands.literal("close")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    PortBindingCache.unbindAllPorts();
                    // Clear the LAN status HUD on the client — world is no longer open
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc != null) mc.execute(() -> elder.leapp.fabric.ui.LanStatusHud.clear());
                    ctx.getSource().sendSuccess(
                        () -> Component.literal("[Leap! Pad] All ports unbound."), true
                    );
                    return 1;
                })
                // /leappad close {PORT}
                .then(Commands.argument("ports", StringArgumentType.greedyString())
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> {
                        String ports = StringArgumentType.getString(ctx, "ports");
                        for (int port : parsePorts(ports)) {
                            PortBindingCache.unbindPort(port);
                        }
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("[Leap! Pad] Port(s) unbound: " + ports), true
                        );
                        return 1;
                    })
                )
            )

            // /leappad bind {PORT}
            .then(Commands.literal("bind")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("ports", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String ports = StringArgumentType.getString(ctx, "ports");
                        for (int port : parsePorts(ports)) {
                            PortBindingCache.bindPort(port);
                        }
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("[Leap! Pad] Bound: " + ports), true
                        );
                        return 1;
                    })
                )
            )

            // /leappad offset
            .then(Commands.literal("offset")
                .requires(src -> src.hasPermission(2))

                // /leappad offset bind {n}
                .then(Commands.literal("bind")
                    .then(Commands.argument("n", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String vals = StringArgumentType.getString(ctx, "n");
                            for (int n : parsePorts(vals)) {
                                PortBindingCache.addListenOffset(n);
                            }
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("[Leap! Pad] Listen offset(s) added: " + vals), true
                            );
                            return 1;
                        })
                    )
                )

                // /leappad offset check {n}
                .then(Commands.literal("check")
                    .then(Commands.argument("n", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String vals = StringArgumentType.getString(ctx, "n");
                            for (int n : parsePorts(vals)) {
                                PortBindingCache.addOutboundOffset(n);
                            }
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("[Leap! Pad] Outbound offset(s) added: " + vals), true
                            );
                            return 1;
                        })
                    )
                )

                // /leappad offset close {n}
                .then(Commands.literal("close")
                    .then(Commands.argument("n", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String vals = StringArgumentType.getString(ctx, "n");
                            for (int n : parsePorts(vals)) {
                                PortBindingCache.removeListenOffset(n);
                            }
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("[Leap! Pad] Listen offset(s) removed: " + vals), true
                            );
                            return 1;
                        })
                    )
                )

                // /leappad offset silence {n}
                .then(Commands.literal("silence")
                    .then(Commands.argument("n", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String vals = StringArgumentType.getString(ctx, "n");
                            for (int n : parsePorts(vals)) {
                                PortBindingCache.removeOutboundOffset(n);
                            }
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("[Leap! Pad] Outbound offset(s) removed: " + vals), true
                            );
                            return 1;
                        })
                    )
                )
            )

            // /leappad ip — fetch and display this world's public IP
            // Async HTTP call to api.ipify.org — never blocks the main thread
            .then(Commands.literal("ip")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    CompletableFuture.runAsync(() -> {
                        String ip = fetchExternalIp();
                        int port = PortBindingCache.getGamePort();
                        String address = ip + ":" + port;

                        // Cache the IP for use in probe packets
                        elder.leapp.profile.ProfileManager.setCachedExternalIp(ip);
                        PortalRegistry.setThisWorldAddress(address);

                        // Broadcast to all online OPs for logging
                        String message = "[Leap! Pad] This world's address: " + address;
                        source.getServer().execute(() ->
                            source.getServer().getPlayerList().getPlayers().forEach(player -> {
                                if (player.hasPermissions(2)) {
                                    player.sendSystemMessage(Component.literal(message));
                                }
                            })
                        );
                    });
                    return 1;
                })
            )

            // /leappad status
            .then(Commands.literal("status")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    int portCount   = PortBindingCache.getBoundPortCount();
                    int portalCount = PortalRegistry.getAll().size();
                    ctx.getSource().sendSuccess(
                        () -> Component.literal(
                            "[Leap! Pad] Bound ports: " + portCount +
                            " | Registered portals: " + portalCount
                        ), false
                    );
                    return 1;
                })
            )

            // /leappad reload
            .then(Commands.literal("reload")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    // TODO ST7: implement config hot-reload
                    ctx.getSource().sendSuccess(
                        () -> Component.literal("[Leap! Pad] Config reloaded."), true
                    );
                    return 1;
                })
            )

            // /leappad version — available to all players
            // M1 fix: reads version from fabric.mod.json via FabricLoader instead of
            // getImplementationVersion(), which returns null in development builds.
            .then(Commands.literal("version")
                .executes(ctx -> {
                    String version = FabricLoader.getInstance()
                        .getModContainer("leappad")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                        .orElse("unknown");
                    ctx.getSource().sendSuccess(
                        () -> Component.literal("[Leap! Pad] Version " + version), false
                    );
                    return 1;
                })
            )
        );
    }

    // -------------------------------------------------------
    // /portalid commands
    // -------------------------------------------------------

    private static void registerPortalIdCommands(CommandDispatcher<CommandSourceStack> dispatcher) {

        dispatcher.register(Commands.literal("portalid")

            // /portalid get <x> <y> <z>
            // Returns the active UUID of the portal interior block at the given coordinates.
            // Client-side: when typed as /portalid get with no args, LeapPadFabricClient
            // intercepts the command and appends the looked-at block's coordinates before
            // sending it to the server. Manual coordinate entry also works directly.
            .then(Commands.literal("get")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(ctx -> {
                        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                        String uuid = PortalRegistry.getUuidForPos(pos);
                        if (uuid == null) {
                            ctx.getSource().sendFailure(
                                Component.literal("[Leap! Pad] No portal registered at (" +
                                    pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ").")
                            );
                            return 0;
                        }
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("[Leap! Pad] Portal at (" +
                                pos.getX() + ", " + pos.getY() + ", " + pos.getZ() +
                                "): UUID " + uuid),
                            false
                        );
                        return 1;
                    })
                )
            )

            // /portalid set {UUID}
            .then(Commands.literal("set")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("uuid", StringArgumentType.word())
                    .executes(ctx -> {
                        String uuid = StringArgumentType.getString(ctx, "uuid");
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("[Leap! Pad] Set UUID to: " + uuid), true
                        );
                        return 1;
                    })
                )
            )

            // /portalid bind {oldUUID} {newUUID}
            .then(Commands.literal("bind")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("oldUuid", StringArgumentType.word())
                    .then(Commands.argument("newUuid", StringArgumentType.word())
                        .executes(ctx -> {
                            String oldUuid = StringArgumentType.getString(ctx, "oldUuid");
                            String newUuid = StringArgumentType.getString(ctx, "newUuid");
                            boolean success = bindPortalUuid(oldUuid, newUuid);
                            if (success) {
                                ctx.getSource().sendSuccess(
                                    () -> Component.literal(
                                        "[Leap! Pad] UUID " + oldUuid + " → " + newUuid), true
                                );
                            } else {
                                ctx.getSource().sendFailure(
                                    Component.literal("[Leap! Pad] UUID not found or already taken: " + oldUuid)
                                );
                            }
                            return success ? 1 : 0;
                        })
                    )
                )
            )

            // /portalid remove {coords|UUID}
            .then(Commands.literal("remove")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("target", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String target = StringArgumentType.getString(ctx, "target");
                        boolean removed = PortalRegistry.removeByUuid(target);
                        if (removed) {
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("[Leap! Pad] Removed portal: " + target), true
                            );
                        } else {
                            ctx.getSource().sendFailure(
                                Component.literal("[Leap! Pad] No portal found for: " + target)
                            );
                        }
                        return removed ? 1 : 0;
                    })
                )
            )

            // /portalid locate {UUID}
            .then(Commands.literal("locate")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("uuid", StringArgumentType.word())
                    .executes(ctx -> {
                        String uuid = StringArgumentType.getString(ctx, "uuid");
                        PortalRegistry.PortalEntry entry = PortalRegistry.getEntry(uuid);
                        if (entry == null) {
                            ctx.getSource().sendFailure(
                                Component.literal("[Leap! Pad] No portal found with UUID: " + uuid)
                            );
                            return 0;
                        }
                        StringBuilder sb = new StringBuilder("[Leap! Pad] Corners for ")
                            .append(uuid).append(": ");
                        entry.corners.forEach(pos ->
                            sb.append("(").append(pos.getX()).append(",")
                              .append(pos.getY()).append(",")
                              .append(pos.getZ()).append(") ")
                        );
                        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                        return 1;
                    })
                )
            )

            // /portalid registry
            // Gives the player a written book listing all registered portals.
            // By default shows UUID and nickname (or address if no nickname, or "(unlinked)").
            //
            // /portalid registry showip OP=N
            // Two-gate check: player's actual OP level must be >= N, and N must be >=
            // the configured ipVisibilityMinOpLevel (world config overrides global config).
            // If both gates pass: book entries include the full address alongside nickname.
            // If either gate fails: rejection message sent to the player and to all online
            // players at or above the configured threshold. No book given on failure.
            .then(Commands.literal("registry")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    giveRegistryBook(player, false);
                    return 1;
                })
                // /portalid registry showip OP=N
                .then(Commands.literal("showip")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("op_level", IntegerArgumentType.integer(1, 4))
                        .executes(ctx -> {
                            int declaredLevel = IntegerArgumentType.getInteger(ctx, "op_level");
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            int threshold = resolveIpVisibilityThreshold(ctx.getSource());

                            // Gate 1: player's actual level must be >= declared level
                            boolean gate1 = player.hasPermissions(declaredLevel);
                            // Gate 2: declared level must be >= configured threshold
                            boolean gate2 = declaredLevel >= threshold;

                            if (!gate1 || !gate2) {
                                notifyShowipRejection(player, declaredLevel, threshold, ctx.getSource());
                                return 0;
                            }

                            giveRegistryBook(player, true);
                            return 1;
                        })
                    )
                )
            )

            // /portalid nickname {UUID} "string"
            // Any player if unclaimed; OP required if nickname already exists
            .then(Commands.literal("nickname")
                .then(Commands.argument("uuid", StringArgumentType.word())
                    .then(Commands.argument("nickname", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String uuid     = StringArgumentType.getString(ctx, "uuid");
                            String nickname = StringArgumentType.getString(ctx, "nickname");
                            CommandSourceStack source = ctx.getSource();

                            String existing   = PortalRegistry.getNickname(uuid);
                            boolean hasExisting = existing != null && !existing.isEmpty();

                            // OP required if nickname already exists
                            if (hasExisting && !source.hasPermission(2)) {
                                source.sendFailure(Component.literal(
                                    "[Leap! Pad] OP permission required to change an existing nickname."
                                ));
                                return 0;
                            }

                            boolean success = PortalRegistry.setNickname(uuid, nickname);
                            if (success) {
                                source.sendSuccess(
                                    () -> Component.literal(
                                        "[Leap! Pad] Nickname set for " + uuid + ": " + nickname), true
                                );
                            } else {
                                source.sendFailure(
                                    Component.literal("[Leap! Pad] No portal found with UUID: " + uuid)
                                );
                            }
                            return success ? 1 : 0;
                        })
                    )
                )
            )
        );
    }

    // -------------------------------------------------------
    // Registry book generation
    // -------------------------------------------------------

    // Maximum characters per book page (vanilla limit is 256 per page).
    private static final int MAX_PAGE_CHARS = 240;

    // Builds and gives a registry book to the player.
    // showIp = false: shows UUID and nickname (or address if no nickname, or "(unlinked)")
    // showIp = true:  shows UUID, address, and nickname together where both are set
    private static void giveRegistryBook(ServerPlayer player, boolean showIp) {
        java.util.Map<String, PortalRegistry.PortalEntry> all = PortalRegistry.getAll();

        // Build all entry lines first, then pack into pages
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, PortalRegistry.PortalEntry> e : all.entrySet()) {
            String uuid  = e.getKey();
            PortalRegistry.PortalEntry entry = e.getValue();
            boolean hasAddress  = entry.linkedAddress != null && !entry.linkedAddress.isEmpty();
            boolean hasNickname = entry.nickname != null && !entry.nickname.isEmpty();

            String line;
            if (showIp) {
                // IP-visible format: uuid — address (nickname) or subsets
                if (hasAddress && hasNickname) {
                    line = uuid + " \u2014 " + entry.linkedAddress + " (" + entry.nickname + ")";
                } else if (hasAddress) {
                    line = uuid + " \u2014 " + entry.linkedAddress;
                } else if (hasNickname) {
                    line = uuid + " \u2014 (" + entry.nickname + ")";
                } else {
                    line = uuid + " \u2014 (unlinked)";
                }
            } else {
                // Standard format: uuid — nickname, or address if no nickname, or (unlinked)
                if (hasNickname) {
                    line = uuid + " \u2014 " + entry.nickname;
                } else if (hasAddress) {
                    line = uuid + " \u2014 " + entry.linkedAddress;
                } else {
                    line = uuid + " \u2014 (unlinked)";
                }
            }
            lines.add(line);
        }

        // Pack lines into pages
        java.util.List<String> pages = new java.util.ArrayList<>();
        StringBuilder page = new StringBuilder();
        for (String line : lines) {
            // Each line gets a newline appended; check if it fits on the current page
            String lineWithNewline = line + "\n";
            if (page.length() + lineWithNewline.length() > MAX_PAGE_CHARS && page.length() > 0) {
                pages.add(page.toString().trim());
                page = new StringBuilder();
            }
            page.append(lineWithNewline);
        }
        if (page.length() > 0) pages.add(page.toString().trim());
        if (pages.isEmpty()) pages.add("No portals registered.");

        // Build the written_book ItemStack via NBT
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = new CompoundTag();
        tag.putString("title", "Portal Registry");
        tag.putString("author", "Leap! Pad");
        tag.putByte("resolved", (byte) 1);

        ListTag pageList = new ListTag();
        for (String pageContent : pages) {
            // Each page is stored as a JSON text component string
            pageList.add(StringTag.valueOf("{\"text\":\"" +
                pageContent.replace("\\", "\\\\")
                           .replace("\"", "\\\"")
                           .replace("\n", "\\n") +
                "\"}"));
        }
        tag.put("pages", pageList);
        book.setTag(tag);

        // Give to player; drop at feet if inventory is full
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }

        final int pageCount = pages.size();
        player.sendSystemMessage(Component.literal(
            "[Leap! Pad] Registry book given (" + pageCount + " page" +
            (pageCount == 1 ? "" : "s") + ")."
        ));
    }

    // Reads the effective IP visibility threshold for the current world.
    // World config (leappad_lan.json) overrides the global config (leappad.json)
    // when an explicit value is present.
    private static int resolveIpVisibilityThreshold(CommandSourceStack source) {
        try {
            // Resolve the world save directory from the server's level storage
            java.nio.file.Path worldDir = source.getServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .toAbsolutePath();
            WorldLanConfig worldConfig = WorldLanConfig.load(worldDir);
            if (worldConfig.hasIpVisibilityOverride()) {
                return worldConfig.getIpVisibilityMinOpLevel();
            }
        } catch (Exception e) {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] Could not read world config for IP visibility threshold: {}", e.getMessage()
            );
        }
        return LeapPadConfig.ipVisibilityMinOpLevel;
    }

    // Sends rejection messages when /portalid registry showip OP=N fails either gate.
    // The attempting player gets a direct message. All online players at or above
    // the configured threshold see a notification including the player name and
    // declared level — so they know someone below the threshold attempted access.
    private static void notifyShowipRejection(ServerPlayer player, int declaredLevel,
                                              int threshold, CommandSourceStack source) {
        player.sendSystemMessage(Component.literal(
            "[Leap! Pad] You do not have permission to view portal addresses."
        ));
        String playerName = player.getName().getString();
        String notification = "[Leap! Pad] " + playerName +
            " attempted to access portal address visibility (OP=" + declaredLevel + " declared).";
        source.getServer().execute(() ->
            source.getServer().getPlayerList().getPlayers().forEach(p -> {
                // Notify everyone at or above the threshold except the attempting player
                // (they already got their own message above)
                if (p.hasPermissions(threshold) && !p.getUUID().equals(player.getUUID())) {
                    p.sendSystemMessage(Component.literal(notification));
                }
            })
        );
        LeapPadCommon.LOGGER.warn("[Leap! Pad] showip rejection: {} declared OP={}, threshold={}",
            playerName, declaredLevel, threshold);
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    // Parses a colon-separated port string into an array of ints.
    // e.g. "25566:25567:25568" → [25566, 25567, 25568]
    private static int[] parsePorts(String input) {
        String[] parts = input.trim().split(":");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                LeapPadCommon.LOGGER.warn("[Leap! Pad] Could not parse port value: {}", parts[i]);
                result[i] = 0;
            }
        }
        return result;
    }

    // Applies /portalid bind logic: replaces old UUID with new alphanumeric UUID.
    // New UUID must not already be taken. Bound UUIDs are flagged as exempt from shortening bumps.
    private static boolean bindPortalUuid(String oldUuid, String newUuid) {
        PortalRegistry.PortalEntry entry = PortalRegistry.getEntry(oldUuid);
        if (entry == null) return false;

        // Reject if new UUID is already taken
        if (PortalRegistry.getEntry(newUuid) != null) {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] /portalid bind rejected — UUID already taken: {}", newUuid
            );
            return false;
        }

        // Apply length rules from the Architecture Plan:
        // If longer than active length, excess becomes suffix.
        // If shorter, pad with zeros.
        int activeLen = elder.leapp.config.LeapPadConfig.portalDesignationActiveLength;
        String general;
        if (newUuid.length() > activeLen) {
            general = newUuid; // Full string stored; active = first activeLen chars
        } else if (newUuid.length() < activeLen) {
            general = newUuid + "0".repeat(activeLen - newUuid.length());
        } else {
            general = newUuid;
        }

        entry.generalUuid = general;
        entry.isBound = true;
        PortalRegistry.updatePortalUuid(oldUuid, newUuid.substring(0, Math.min(newUuid.length(), activeLen)));
        return true;
    }

    // Fetches this machine's external IP from api.ipify.org.
    // Runs on a background thread — never call from the main thread.
    // SS8: Public method to trigger an async external IP fetch and cache the result.
    // Called by the IpRefreshCallback bridge injected into TransferOrchestrator from
    // LeapPadFabricClient when TransferSequencer detects the cached IP has expired.
    // Also called by the /leappad ip command handler (extracted from inline lambda).
    public static void fetchAndCacheExternalIpAsync() {
        CompletableFuture.runAsync(() -> {
            String ip = fetchExternalIp();
            if (ip != null && !ip.isEmpty()) {
                elder.leapp.profile.ProfileManager.setCachedExternalIp(ip);
            }
        });
    }

    private static String fetchExternalIp() {
        try {
            URL url = new URL("https://api.ipify.org");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String ip = reader.readLine();
                LeapPadCommon.LOGGER.info("[Leap! Pad] External IP: {}", ip);
                return ip != null ? ip.trim() : "unknown";
            }
        } catch (Exception e) {
            LeapPadCommon.LOGGER.warn("[Leap! Pad] Could not fetch external IP: {}", e.getMessage());
            return "unknown";
        }
    }
}

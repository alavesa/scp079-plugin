package fi.alavesa.scp079.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fi.alavesa.scp079.Scp079Plugin;
import fi.alavesa.scp079.config.Messages;
import fi.alavesa.scp079.config.SecretStore;
import fi.alavesa.scp079.config.Settings;
import fi.alavesa.scp079.gemini.ReplySanitizer;
import fi.alavesa.scp079.gemini.Turn;
import fi.alavesa.scp079.pack.PackService;
import fi.alavesa.scp079.render.ActiveModel;
import fi.alavesa.scp079.unit.Unit;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /scp079} (alias {@code /079}), registered through Paper's Brigadier command API: the client gets
 * the whole tree, so sub-commands, unit ids and player names complete while typing. Every sub-command
 * needs {@code scp079.admin}; talking to SCP-079 is not a command.
 */
public final class Scp079Command {

    private static final double REMOVE_RADIUS = 5, SPAWN_REACH = 6;

    private final Scp079Plugin plugin;

    public Scp079Command(Scp079Plugin plugin) { this.plugin = plugin; }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(tree().build(), "SCP-079: API key, units, models and the resource pack", List.of("079")));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("scp079").requires(s -> s.getSender().hasPermission(Scp079Plugin.PERMISSION_ADMIN))
            .then(Commands.literal("setkey")
                .then(Commands.argument("apiKey", StringArgumentType.greedyString()).executes(this::setKey)))
            .then(Commands.literal("removekey").executes(this::removeKey))
            .then(Commands.literal("test").executes(this::test))
            .then(Commands.literal("status").executes(this::status))
            .then(Commands.literal("give").executes(this::give))
            .then(Commands.literal("spawn").executes(this::spawn))
            .then(Commands.literal("remove")
                .executes(c -> remove(c, null))
                .then(Commands.argument("id", IntegerArgumentType.integer(1)).suggests(this::suggestUnits)
                    .executes(c -> remove(c, IntegerArgumentType.getInteger(c, "id")))))
            .then(Commands.literal("list").executes(this::list))
            .then(Commands.literal("reload").executes(this::reload))
            .then(Commands.literal("pack")
                .then(Commands.literal("build").executes(this::packBuild))
                .then(Commands.literal("info").executes(this::packInfo)))
            .then(Commands.literal("resetmemory")
                .executes(c -> resetMemory(c, null))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(this::suggestPlayers)
                    .executes(c -> resetMemory(c, StringArgumentType.getString(c, "player")))));
    }

    private CompletableFuture<Suggestions> suggestUnits(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        for (Unit unit : plugin.units().sorted()) {
            String id = String.valueOf(unit.id());
            if (id.startsWith(builder.getRemaining())) builder.suggest(id);
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String typed = builder.getRemainingLowerCase();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(player.getName());
        }
        return builder.buildFuture();
    }

    private Messages msg() { return plugin.messages(); }

    /** @return the player running the command, or null (after telling the sender) when it is not one */
    private Player player(CommandContext<CommandSourceStack> c) {
        if (c.getSource().getExecutor() instanceof Player player) return player;
        msg().send(c.getSource().getSender(), "command.players-only");
        return null;
    }

    // ------------------------------------------------------------------ key

    private int setKey(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String key = StringArgumentType.getString(c, "apiKey").trim();
        if (key.isEmpty() || key.contains(" ")) {
            msg().send(sender, "key.invalid");
            return 0;
        }
        if (!plugin.secrets().set(key)) {
            msg().send(sender, "key.save-failed");
            return 0;
        }
        msg().send(sender, "key.set", Messages.text("key", SecretStore.mask(key)));
        if (plugin.secrets().fromEnvironment()) msg().send(sender, "key.env-override", Messages.text("env", SecretStore.ENV));
        return Command.SINGLE_SUCCESS;
    }

    private int removeKey(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        plugin.secrets().remove();
        msg().send(sender, "key.removed");
        if (plugin.secrets().fromEnvironment()) msg().send(sender, "key.env-override", Messages.text("env", SecretStore.ENV));
        return Command.SINGLE_SUCCESS;
    }

    private int test(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Settings settings = plugin.settings();
        msg().send(sender, "test.running", Messages.text("model", settings.gemini().model()));
        plugin.gemini().generate(plugin.systemPrompt(), List.of(Turn.user("(Connection test. Confirm in one short sentence that you are online.)")))
            .thenAccept(result -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.ok()) {
                        msg().send(sender, "test.ok", Messages.text("status", result.status()), Messages.text("millis", result.millis()),
                            Messages.text("reply", ReplySanitizer.clean(result.text(), settings.conversation().maxSentences())));
                    } else {
                        msg().send(sender, "test.failed", Messages.text("status", result.status() == 0 ? "-" : String.valueOf(result.status())),
                            Messages.text("error", result.error().name()), Messages.text("detail", result.detail()));
                    }
                });
            });
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ status

    private int status(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Settings settings = plugin.settings();
        SecretStore secrets = plugin.secrets();
        Scp079Plugin.ModelState state = plugin.modelState();
        ActiveModel active = plugin.activeModel();

        msg().send(sender, "status.header", Messages.text("version", plugin.getPluginMeta().getVersion()));
        if (secrets.hasKey()) {
            msg().send(sender, "status.key-set", Messages.text("key", SecretStore.mask(secrets.key())),
                Messages.text("source", secrets.fromEnvironment() ? SecretStore.ENV : "secrets.yml"));
        } else {
            msg().send(sender, "status.key-missing");
        }
        msg().send(sender, "status.model", Messages.text("model", settings.gemini().model()));
        msg().send(sender, "status.units", Messages.text("count", plugin.units().count()));
        msg().send(sender, "status.render-mode", Messages.text("mode", settings.visual().renderMode().name()));
        if (settings.visual().renderMode() == Settings.RenderMode.PACK_MODEL) msg().send(sender, "status.pack-model-warning");
        if (state != null && state.result().packWritten()) {
            msg().send(sender, "status.pack-generated", Messages.text("files", state.result().fileCount()), Messages.text("sha1", state.result().sha1()));
        } else {
            msg().send(sender, "status.pack-missing");
        }
        if (state != null) {
            msg().send(sender, "status.pack-delivery", Messages.text("delivery", plugin.delivery().delivery().name().toLowerCase(Locale.ROOT).replace('_', '-')),
                Messages.text("loaded", plugin.delivery().loadedCount()), Messages.text("online", Bukkit.getOnlinePlayers().size()));
            msg().send(sender, settings.captions().enabled() && state.captionShader() ? "status.captions-on" : "status.captions-off", Messages.text("shader", state.shader()));
        }
        if (active != null) {
            msg().send(sender, "status.bbmodel", Messages.text("file", active.fileName()), Messages.text("cubes", active.plan().sourceCubes()),
                Messages.text("displays", active.plan().parts().size()));
        } else {
            msg().send(sender, "status.bbmodel-missing");
        }
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ units

    private int give(CommandContext<CommandSourceStack> c) {
        Player player = player(c);
        if (player == null) return 0;
        // whatever does not fit is dropped at the player's feet rather than lost
        for (ItemStack overflow : player.getInventory().addItem(plugin.items().create()).values()) player.getWorld().dropItem(player.getLocation(), overflow);
        msg().send(player, "give.done");
        return Command.SINGLE_SUCCESS;
    }

    /**
     * A player places the unit against the block they look at (or at their feet); the console and
     * {@code /execute positioned ... run scp079 spawn} place it exactly at the command's position.
     */
    private int spawn(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Player player = c.getSource().getExecutor() instanceof Player p ? p : null;
        Location at;
        if (player != null) {
            Block target = player.getTargetBlockExact((int) SPAWN_REACH);
            if (target != null) {
                BlockFace face = player.getTargetBlockFace((int) SPAWN_REACH);
                at = target.getRelative(face != null ? face : BlockFace.UP).getLocation().add(0.5, 0, 0.5);
            } else {
                Location feet = player.getLocation();
                at = new Location(feet.getWorld(), feet.getBlockX() + 0.5, feet.getY(), feet.getBlockZ() + 0.5);
            }
        } else {
            at = c.getSource().getLocation();
            if (at.getWorld() == null) {
                msg().send(sender, "command.players-only");
                return 0;
            }
        }
        Unit unit = plugin.placeUnit(at, player);
        msg().send(sender, "place.done", Messages.text("id", unit.id()));
        if (plugin.activeModel() == null) msg().send(sender, "status.bbmodel-missing");
        return Command.SINGLE_SUCCESS;
    }

    private int remove(CommandContext<CommandSourceStack> c, Integer id) {
        CommandSender sender = c.getSource().getSender();
        Unit unit;
        if (id != null) {
            unit = plugin.units().get(id);
            if (unit == null) {
                msg().send(sender, "remove.unknown", Messages.text("id", id));
                return 0;
            }
        } else {
            Location at = c.getSource().getLocation();
            unit = at.getWorld() == null ? null : plugin.units().nearest(at, REMOVE_RADIUS);
            if (unit == null) {
                msg().send(sender, "remove.none-near", Messages.text("radius", (long) REMOVE_RADIUS));
                return 0;
            }
        }
        plugin.removeUnit(unit);
        msg().send(sender, "remove.done", Messages.text("id", unit.id()));
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        List<Unit> units = plugin.units().sorted();
        if (units.isEmpty()) {
            msg().send(sender, "list.empty");
            return 0;
        }
        msg().send(sender, "list.header", Messages.text("count", units.size()));
        for (Unit unit : units) {
            msg().send(sender, "list.entry", Messages.text("id", unit.id()), Messages.text("world", unit.worldName()),
                Messages.text("x", coordinate(unit.x())), Messages.text("y", coordinate(unit.y())), Messages.text("z", coordinate(unit.z())));
        }
        return units.size();
    }

    private static String coordinate(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    // ------------------------------------------------------------------ reload / pack

    private int reload(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        boolean started = plugin.rebuild(true, state -> {
            // the messages have just been reloaded: fetch them again
            msg().send(sender, "reload.done", Messages.text("models", state.result().models().size()), Messages.text("warnings", state.warnings().size()));
            if (!state.warnings().isEmpty()) msg().send(sender, "reload.see-pack-info");
        });
        msg().send(sender, started ? "reload.started" : "reload.busy");
        return started ? Command.SINGLE_SUCCESS : 0;
    }

    private int packBuild(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        boolean started = plugin.rebuild(false, state -> {
            if (state.result().packWritten()) {
                msg().send(sender, "pack.built", Messages.text("files", state.result().fileCount()), Messages.text("sha1", state.result().sha1()),
                    Messages.text("warnings", state.warnings().size()));
            } else {
                msg().send(sender, "pack.build-failed");
            }
            if (!state.warnings().isEmpty()) msg().send(sender, "reload.see-pack-info");
        });
        msg().send(sender, started ? "pack.building" : "reload.busy");
        return started ? Command.SINGLE_SUCCESS : 0;
    }

    private int packInfo(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Scp079Plugin.ModelState state = plugin.modelState();
        if (state == null) {
            msg().send(sender, "status.pack-missing");
            return 0;
        }
        PackService.Result result = state.result();
        ActiveModel active = state.active();
        msg().send(sender, "pack.info-header");
        msg().send(sender, "pack.info-zip", Messages.text("path", result.zip().toString()));
        msg().send(sender, "pack.info-folder", Messages.text("path", result.folder().toString()));
        if (result.packWritten()) msg().send(sender, "pack.info-sha1", Messages.text("sha1", result.sha1()), Messages.text("files", result.fileCount()));
        else msg().send(sender, "status.pack-missing");
        for (PackService.LoadedModel model : result.models().values()) {
            boolean isActive = active != null && active.id().equals(model.id());
            msg().send(sender, isActive ? "pack.info-model-active" : "pack.info-model", Messages.text("file", model.fileName()),
                Messages.text("id", Settings.NAMESPACE + ":" + model.id()), Messages.text("cubes", model.geometry().elements.size()),
                Messages.text("textures", model.model().textures.size()),
                Messages.text("displays", isActive ? String.valueOf(active.plan().parts().size()) : "-"));
        }
        if (state.warnings().isEmpty()) {
            msg().send(sender, "pack.info-no-warnings");
        } else {
            msg().send(sender, "pack.info-warnings", Messages.text("count", state.warnings().size()));
            for (String warning : state.warnings()) msg().send(sender, "pack.info-warning", Messages.text("warning", warning));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ memory

    private int resetMemory(CommandContext<CommandSourceStack> c, String name) {
        CommandSender sender = c.getSource().getSender();
        if (name == null) {
            msg().send(sender, "memory.reset-all", Messages.text("count", plugin.conversations().resetAll()));
            return Command.SINGLE_SUCCESS;
        }
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            msg().send(sender, "memory.unknown-player", Messages.text("player", name));
            return 0;
        }
        plugin.conversations().reset(target.getUniqueId());
        msg().send(sender, "memory.reset-player", Messages.text("player", target.getName()));
        return Command.SINGLE_SUCCESS;
    }
}

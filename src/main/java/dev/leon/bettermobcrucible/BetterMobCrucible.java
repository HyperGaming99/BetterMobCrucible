package dev.leon.bettermobcrucible;

import eu.northsoft.bettermob.api.BetterMobAPI;
import eu.northsoft.bettermob.api.MechanicContext;
import eu.northsoft.bettermob.api.PlaceholderContext;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Zusatz-Trigger fuer BetterMob-Items: BetterMob liest die Items ({@code items/*.yml}) und
 * kennt {@code ~onUse}; dieses Plugin ergaenzt {@code ~onSwing}, {@code ~onPressF},
 * {@code ~onEquip}, {@code ~onPickup}, {@code ~onDrop} und {@code ~onTimer:<ticks>}.
 */
public final class BetterMobCrucible extends JavaPlugin implements Listener {
    private static final Pattern TRIGGER = Pattern.compile("\\s*~on([A-Za-z]+)(?::(\\d+))?\\s*$");
    private static final String PACK_NAME = "BetterMobCrucible";
    /** Wird von BetterMob selbst ausgefuehrt. */
    private static final String NATIVE_TRIGGER = "use";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private record TimerEntry(String skill, int interval) {}

    /** Unveraenderlicher Stand nach einem Reload: wird in einem Schritt getauscht, nie halb befuellt gelesen. */
    private record Snapshot(Map<String, Map<String, String>> dispatch, Map<String, List<TimerEntry>> timers,
                            Set<String> triggers, int timerPeriod) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Set.of(), 0);
    }

    private final Map<String, NamespacedKey> nbtKeys = new ConcurrentHashMap<>();
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private BukkitTask timerTask;
    private long tick;
    private Plugin betterMob;
    private BetterMobAPI api;
    private NamespacedKey itemKey;

    @Override
    public void onEnable() {
        betterMob = getServer().getPluginManager().getPlugin("BetterMob");
        getServer().getPluginManager().registerEvents(this, this);

        api = BetterMobAPI.get();
        itemKey = new NamespacedKey(betterMob, "item_id");
        api.registerMechanic(this, "setitemnbt", this::setItemNbt);
        api.registerMechanic(this, "setlore", this::setLore);
        try {
            api.registerPlaceholder(this, "itemnbt", this::nbtPlaceholder);
            api.registerPlaceholder(this, "item", this::itemPlaceholder);
        } catch (LinkageError e) {
            getLogger().warning("Diese BetterMob-Version hat keine Platzhalter-API - <itemnbt.key> ist aus. (" + e + ")");
        }

        getServer().getScheduler().runTask(this, this::rebuild);
    }

    NamespacedKey nbtKey(String key) {
        return nbtKeys.computeIfAbsent(key.toLowerCase(Locale.ROOT), k -> new NamespacedKey(this, "nbt/" + k));
    }

    // ---- Laden ------------------------------------------------------------------------

    /** Item-Dateien von BetterMob lesen, Skills als Pack in BetterMob ablegen und BetterMob bei Aenderung neu laden. */
    private void rebuild() {
        startRebuild(null);
    }

    private void startRebuild(CommandSender sender) {
        getServer().getAsyncScheduler().runNow(this, task -> load(sender));
    }

    private synchronized void load(CommandSender sender) {
        Map<String, Map<String, String>> dispatch = new HashMap<>();
        Map<String, List<TimerEntry>> timers = new HashMap<>();
        Set<String> triggers = new HashSet<>();
        int period = 0;

        YamlConfiguration generated = new YamlConfiguration();
        int registered = 0;
        int items = 0;
        for (File file : itemFiles()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String itemId : yaml.getKeys(false)) {
                ConfigurationSection section = yaml.getConfigurationSection(itemId);
                if (section == null || !section.contains("Id")) continue;
                String key = itemId.toLowerCase(Locale.ROOT);
                if (dispatch.containsKey(key) || timers.containsKey(key)) continue;

                Map<String, List<String>> groups = groupByTrigger(section.getStringList("Skills"));
                groups.remove(NATIVE_TRIGGER);
                if (groups.isEmpty()) continue;
                items++;
                for (Map.Entry<String, List<String>> group : groups.entrySet()) {
                    String trigger = group.getKey();
                    String skillName = skillName(itemId, trigger);
                    generated.set(skillName + ".Skills", group.getValue());
                    if (trigger.startsWith("timer:")) {
                        int interval = parseInterval(trigger.substring("timer:".length()));
                        timers.computeIfAbsent(key, k -> new ArrayList<>()).add(new TimerEntry(skillName, interval));
                        period = period == 0 ? interval : gcd(period, interval);
                    } else {
                        dispatch.computeIfAbsent(key, k -> new HashMap<>()).put(trigger, skillName);
                        triggers.add(trigger);
                    }
                    registered++;
                }
            }
        }

        Snapshot loaded = new Snapshot(dispatch, timers, triggers, period);
        boolean skillsChanged = syncSkillPack(generated);
        getLogger().info(registered + " Item-Trigger fuer " + items + " Items geladen.");
        getServer().getGlobalRegionScheduler().run(this, task -> apply(loaded, skillsChanged, sender));
    }

    private void apply(Snapshot loaded, boolean skillsChanged, CommandSender sender) {
        snapshot = loaded;
        scheduleTimer(loaded.timerPeriod());
        if (skillsChanged) {
            api.reload();
            getLogger().info("Item-Skills in BetterMob aktualisiert.");
        }
        if (sender != null) sender.sendMessage("BetterMobCrucible reloaded.");
    }

    private static int parseInterval(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return 20;
        }
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /** Ein einziger Timer im ggT aller Intervalle statt jeden Tick; ohne Timer-Items laeuft gar keiner. */
    private void scheduleTimer(int period) {
        if (timerTask != null) timerTask.cancel();
        timerTask = null;
        tick = 0;
        if (period > 0) timerTask = getServer().getScheduler().runTaskTimer(this, this::runTimers, period, period);
    }

    /** BetterMobs {@code items/} und die {@code Items/}-Ordner aller aktiven Packs. */
    private List<File> itemFiles() {
        File root = betterMob.getDataFolder();
        List<File> folders = new ArrayList<>();
        folders.add(new File(root, "items"));

        List<String> disabled = betterMob.getConfig().getStringList("DisabledPacks").stream()
                .map(v -> v.toLowerCase(Locale.ROOT)).toList();
        File[] packs = new File(root, "packs").listFiles(File::isDirectory);
        if (packs != null) {
            for (File pack : packs) {
                if (pack.getName().equalsIgnoreCase(PACK_NAME)) continue;
                if (disabled.contains(pack.getName().toLowerCase(Locale.ROOT))) continue;
                File[] children = pack.listFiles(File::isDirectory);
                if (children == null) continue;
                for (File child : children) if (child.getName().equalsIgnoreCase("items")) folders.add(child);
            }
        }

        List<File> found = new ArrayList<>();
        for (File folder : folders) {
            if (!folder.isDirectory()) continue;
            try (Stream<Path> paths = Files.walk(folder.toPath())) {
                paths.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".yml")).forEach(p -> found.add(p.toFile()));
            } catch (IOException e) {
                getLogger().warning("Konnte " + folder + " nicht lesen: " + e.getMessage());
            }
        }
        return found;
    }

    private boolean syncSkillPack(YamlConfiguration generated) {
        File target = new File(betterMob.getDataFolder(), "packs/" + PACK_NAME + "/skills/items.yml");
        String content = generated.saveToString();
        try {
            if (target.isFile() && Files.readString(target.toPath()).equals(content)) return false;
            target.getParentFile().mkdirs();
            Files.writeString(target.toPath(), content);
            return true;
        } catch (IOException e) {
            getLogger().warning("Konnte " + target + " nicht schreiben: " + e.getMessage());
            return false;
        }
    }

    static String skillName(String itemId, String trigger) {
        return ("bmc_" + itemId + "_" + trigger).toLowerCase(Locale.ROOT).replace(':', '_').replace('.', '_');
    }

    static Map<String, List<String>> groupByTrigger(List<String> lines) {
        Map<String, List<String>> byTrigger = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher matcher = TRIGGER.matcher(line);
            if (matcher.find()) {
                String trigger = matcher.group(1).toLowerCase(Locale.ROOT);
                if (matcher.group(2) != null) trigger = trigger + ":" + matcher.group(2);
                byTrigger.computeIfAbsent(trigger, k -> new ArrayList<>()).add(line.substring(0, matcher.start()).trim());
            }
        }
        return byTrigger;
    }

    // ---- Items ------------------------------------------------------------------------

    /** BetterMob-Item-ID (klein geschrieben) oder null, wenn der Stack kein BetterMob-Item ist. */
    String itemIdOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        String id = stack.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return id == null ? null : id.toLowerCase(Locale.ROOT);
    }

    // ---- Trigger ----------------------------------------------------------------------

    private void fire(Player player, ItemStack stack, String trigger) {
        Snapshot current = snapshot;
        if (!current.triggers().contains(trigger)) return;
        fire(current, player, itemIdOf(stack), trigger);
    }

    private void fire(Snapshot current, Player player, String itemId, String trigger) {
        if (itemId == null) return;
        Map<String, String> byTrigger = current.dispatch().get(itemId);
        if (byTrigger == null) return;
        String skill = byTrigger.get(trigger);
        if (skill != null) api.runSkill(skill, player);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !snapshot.triggers().contains("swing")) return;
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        switch (event.getAction()) {
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> fire(event.getPlayer(), held, "swing");
            default -> { }
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Snapshot current = snapshot;
        if (!current.triggers().contains("pressf")) return;
        String off = itemIdOf(event.getOffHandItem());
        String main = itemIdOf(event.getMainHandItem());
        fire(current, event.getPlayer(), off, "pressf");
        if (main != null && !main.equals(off)) fire(current, event.getPlayer(), main, "pressf");
    }

    @EventHandler
    public void onHeldSlotChange(PlayerItemHeldEvent event) {
        fire(event.getPlayer(), event.getPlayer().getInventory().getItem(event.getNewSlot()), "equip");
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        Snapshot current = snapshot;
        if (current.triggers().isEmpty() || !(event.getEntity() instanceof Player player)) return;
        String id = itemIdOf(event.getItem().getItemStack());
        fire(current, player, id, "pickup");
        fire(current, player, id, "itempickup");
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Snapshot current = snapshot;
        if (current.triggers().isEmpty()) return;
        String id = itemIdOf(event.getItemDrop().getItemStack());
        fire(current, event.getPlayer(), id, "drop");
        fire(current, event.getPlayer(), id, "itemdrop");
    }

    private void runTimers() {
        Snapshot current = snapshot;
        tick += current.timerPeriod();
        for (Player player : getServer().getOnlinePlayers()) {
            String itemId = itemIdOf(player.getInventory().getItemInMainHand());
            if (itemId == null) continue;
            List<TimerEntry> entries = current.timers().get(itemId);
            if (entries == null) continue;
            for (TimerEntry entry : entries) {
                if (tick % entry.interval() == 0L) api.runSkill(entry.skill(), player);
            }
        }
    }

    // ---- Mechanics --------------------------------------------------------------------

    private static String param(Map<String, String> params, String... names) {
        for (String name : names) {
            String value = params.get(name);
            if (value != null) return value;
        }
        return "";
    }

    private static LivingEntity targetOf(MechanicContext ctx) {
        return ctx.target() instanceof LivingEntity living ? living : ctx.caster();
    }

    private static ItemStack mainHand(Entity entity) {
        if (entity instanceof LivingEntity living && living.getEquipment() != null) {
            ItemStack held = living.getEquipment().getItemInMainHand();
            return held.getType().isAir() ? null : held;
        }
        return null;
    }

    /** {@code setitemnbt{key=x;value=y}} - schreibt einen String-Wert ins gehaltene Item. */
    private void setItemNbt(MechanicContext ctx) {
        LivingEntity target = targetOf(ctx);
        ItemStack held = mainHand(target);
        String key = param(ctx.params(), "key", "k");
        if (held == null || key.isEmpty()) return;
        ItemMeta meta = held.getItemMeta();
        meta.getPersistentDataContainer().set(nbtKey(key), PersistentDataType.STRING, param(ctx.params(), "value", "val", "v"));
        held.setItemMeta(meta);
        target.getEquipment().setItemInMainHand(held);
    }

    /** {@code setlore{lore=Zeile 1|&aZeile 2}} - ersetzt die Lore des gehaltenen Items. */
    private void setLore(MechanicContext ctx) {
        LivingEntity target = targetOf(ctx);
        ItemStack held = mainHand(target);
        String raw = param(ctx.params(), "lore", "l");
        if (held == null) return;
        List<Component> lore = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String line : raw.split("\\|")) lore.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        ItemMeta meta = held.getItemMeta();
        meta.lore(lore);
        held.setItemMeta(meta);
        target.getEquipment().setItemInMainHand(held);
    }

    private String nbtOf(LivingEntity entity, String key) {
        ItemStack held = mainHand(entity);
        if (entity == null || held == null || key == null || key.isEmpty() || !held.hasItemMeta()) return "";
        String value = held.getItemMeta().getPersistentDataContainer().get(nbtKey(key), PersistentDataType.STRING);
        return value == null ? "" : value;
    }

    /** {@code <itemnbt.key>} - gespeicherter Wert aus dem gehaltenen Item des Casters. */
    private String nbtPlaceholder(String key, PlaceholderContext ctx) {
        return nbtOf(ctx.caster(), key);
    }

    /** {@code <item.nbt.key>} und {@code <item.id>} fuer das gehaltene Item des Casters. */
    private String itemPlaceholder(String key, PlaceholderContext ctx) {
        if (key == null) return null;
        if (key.equalsIgnoreCase("id")) {
            String id = ctx.caster() == null ? null : itemIdOf(mainHand(ctx.caster()));
            return id == null ? "" : id;
        }
        if (key.regionMatches(true, 0, "nbt.", 0, 4)) return nbtOf(ctx.caster(), key.substring(4));
        return null;
    }

    // ---- Befehl -----------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                sender.sendMessage("Item triggers: " + snapshot.dispatch());
                sender.sendMessage("Timer skills: " + snapshot.timers());
            }
            case "reload" -> startRebuild(sender);
            case "debug" -> debug(sender);
            default -> sender.sendMessage("/" + label + " <list|reload|debug>  (items: /bettermob give)");
        }
        return true;
    }

    private void debug(CommandSender sender) {
        if (!(sender instanceof Player player)) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            sender.sendMessage("Nothing in your main hand.");
            return;
        }
        sender.sendMessage("BetterMob item: " + itemIdOf(held));
        if (held.hasItemMeta()) {
            PersistentDataContainer pdc = held.getItemMeta().getPersistentDataContainer();
            for (NamespacedKey key : pdc.getKeys()) {
                if (key.getNamespace().equals(getName().toLowerCase(Locale.ROOT)) && key.getKey().startsWith("nbt/")) {
                    sender.sendMessage("  " + key.getKey().substring(4) + " = " + pdc.get(key, PersistentDataType.STRING));
                }
            }
        }
    }
}

package com.github.arboriginal.SaplingsAutoReplant;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class SAR extends JavaPlugin implements Listener {
    private final Random random = new Random();
    private final String metaKS = getClass().getName();

    private int detectPeriod, detectMaxTry, replantChance, replantMaxTry, replantPeriod;
    private SAR instance;

    private Set<Material> dirtLike, ignored, leaves, saplings;

    private HashMap<Material, Material>  crops;
    private HashMap<Material, BlockData> datas;

    // JavaPlugin methods ----------------------------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("sar-reload")) return super.onCommand(sender, command, label, args);
        reloadConfig();
        sender.sendMessage("§7[§2SaplingsAutoReplant§7] Configuration reloaded.");
        return true;
    }

    @Override
    public void onEnable() {
        super.onEnable();
        instance = this;
        leaves   = Tag.LEAVES.getValues();
        saplings = Tag.SAPLINGS.getValues();
        dirtLike = new HashSet<Material>();
        dirtLike.addAll(Arrays.asList(
                Material.COARSE_DIRT, Material.DIRT, Material.FARMLAND, Material.GRASS_BLOCK, Material.PODZOL));
        reloadConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        saveDefaultConfig();
        FileConfiguration c = getConfig();
        c.options().copyDefaults(true);
        setAutoReplantCrops(c.getConfigurationSection("crops"));
        ignored       = parseMaterialList(c.getStringList("emptyBlocks"), true);
        detectMaxTry  = c.getInt("maxDetectGroundAttempts");
        detectPeriod  = c.getInt("detectGroundFreq");
        replantChance = c.getInt("chance");
        replantMaxTry = c.getInt("maxTries");
        replantPeriod = c.getInt("frequency");
        saveConfig();
        datas = new HashMap<Material, BlockData>();
        crops.values().forEach(t -> datas.put(t, Bukkit.createBlockData(t)));
        saplings.forEach(t -> datas.put(t, Bukkit.createBlockData(t)));
    }

    // Listener methods ------------------------------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    private void onItemSpawn(ItemSpawnEvent e) {
        Item      i = e.getEntity();
        ItemStack s = i.getItemStack();
        Material  t = s.getType();
        if (saplings.contains(t)) new GroundDetect(i, t, detectPeriod, detectMaxTry, s, false);
        else {
            Material ct = crops.get(t);
            if (ct != null) new GroundDetect(i, ct, detectPeriod, detectMaxTry, s, true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    private void onPlayerDropItem(PlayerDropItemEvent e) {
        Item     i = e.getItemDrop();
        Material t = i.getItemStack().getType();
        if (saplings.contains(t) || crops.containsValue(t))
            i.setMetadata(metaKS, new FixedMetadataValue(this, e.getPlayer().getUniqueId()));
    }

    // Private methods -------------------------------------------------------------------------------------------------

    private void setAutoReplantCrops(ConfigurationSection cs) {
        crops = new HashMap<Material, Material>();
        if (cs == null) return;
        Map<String, Object> list = cs.getValues(false);
        if (list == null) return;
        list.forEach((k, v) -> {
            Material km = Material.valueOf(k);
            if (km == null) {
                Bukkit.getLogger().warning("Invalid crop material « " + k + " »: Ignored.");
                return;
            }
            Material vm = Material.valueOf(v.toString());
            if (vm == null || !vm.isBlock()) {
                Bukkit.getLogger().warning("Invalid block material « " + v + " »: Ignored.");
                return;
            }
            crops.put(km, vm);
        });
    }

    private Set<Material> parseMaterialList(List<String> sl, boolean onlyBlocks) {
        HashSet<Material> hs = new HashSet<Material>();
        if (!sl.isEmpty()) sl.forEach(s -> {
            Material m = Material.valueOf(s);
            if (m != null && (!onlyBlocks || m.isBlock())) hs.add(m);
        });
        return hs;
    }

    // Private classes -------------------------------------------------------------------------------------------------

    private abstract class SART extends BukkitRunnable {
        protected final boolean   c;
        protected final int       m;
        protected final Item      i;
        protected final ItemStack s;
        protected final Material  t;

        protected int a = 0;

        SART(Item I, Material T, int E, int M, ItemStack S, boolean C) {
            m = M;
            i = I;
            s = S;
            t = T;
            c = C;
            runTaskTimer(instance, E, E);
        }

        @Override
        public void run() {
            if (isCancelled()) return;
            if (++a > m) cancel();
            proceed();
        }

        protected abstract void proceed();
    }

    private class GroundDetect extends SART {
        GroundDetect(Item I, Material T, int E, int M, ItemStack S, boolean C) {
            super(I, T, E, M, S, C);
        }

        @Override
        protected void proceed() {
            if (i.isOnGround() && !leaves.contains(i.getLocation().getBlock().getRelative(BlockFace.DOWN).getType())) {
                cancel();
                if (canPlant()) new Replant(i, t, replantPeriod, replantMaxTry, s, c);
            }
        }

        private boolean canPlant() {
            List<MetadataValue> d = i.getMetadata(metaKS);
            if (d.isEmpty()) return true;
            Player p = Bukkit.getPlayer((UUID) d.get(0).value());
            if (p == null) return false;
            Block           b     = i.getLocation().getBlock();
            BlockPlaceEvent event = new BlockPlaceEvent(b, b.getState(), b.getRelative(BlockFace.DOWN),
                    i.getItemStack(), p, true, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        }
    }

    private class Replant extends SART {
        Replant(Item I, Material T, int E, int M, ItemStack S, boolean B) {
            super(I, T, E, M, S, B);
        }

        @Override
        protected void proceed() {
            Location l = i.getLocation();
            l.setY(Math.ceil(l.getY()));
            Block b = l.getBlock();
            if (!i.isValid() || !canBePlanted(b)) {
                cancel();
                return;
            }
            if (random.nextInt(100) <= replantChance) {
                cancel();
                plant(b);
            }
        }

        private boolean canBePlanted(Block b) {
            if (!ignored.contains(b.getType())) return false;
            if (c) {
                if (b.getRelative(BlockFace.DOWN).getType() != Material.FARMLAND) return false;
            }
            else if (!dirtLike.contains(b.getRelative(BlockFace.DOWN).getType())) return false;
            return true;
        }

        private void plant(Block b) {
            BlockData d = datas.get(t);
            if (d == null) return;
            b.setBlockData(d);
            int amount = s.getAmount();
            if (amount == 1) i.remove();
            else s.setAmount(amount - 1);
        }
    }
}

package com.github.arboriginal.SaplingsAutoReplant;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class SAR extends JavaPlugin implements Listener {
    private final Random random = new Random();
    private final String metaKS = getClass().getName();

    private int detectPeriod, detectMaxTry, replantChance, replantMaxTry, replantPeriod;
    private SAR instance;

    private List<String>  ignored;
    private Set<Material> saplings, dirtLike;

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
        saplings = Tag.SAPLINGS.getValues();
        datas    = new HashMap<Material, BlockData>();
        saplings.forEach(t -> datas.put(t, Bukkit.createBlockData(t)));
        dirtLike = new HashSet<Material>();
        dirtLike.addAll(Arrays.asList(Material.COARSE_DIRT, Material.DIRT, Material.GRASS_BLOCK, Material.PODZOL));
        reloadConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        saveDefaultConfig();
        FileConfiguration c = getConfig();
        c.options().copyDefaults(true);
        ignored       = c.getStringList("emptyBlocks");
        detectMaxTry  = c.getInt("maxDetectGroundAttempts");
        detectPeriod  = c.getInt("detectGroundFreq");
        replantChance = c.getInt("chance");
        replantMaxTry = c.getInt("maxTries");
        replantPeriod = c.getInt("frequency");
        saveConfig();
    }

    // Listener methods ------------------------------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    private void onItemSpawn(ItemSpawnEvent e) {
        Item      i = e.getEntity();
        ItemStack s = i.getItemStack();
        Material  t = s.getType();
        if (saplings.contains(t)) new GroundDetect(i, t, detectPeriod, detectMaxTry, s);
    }

    @EventHandler(ignoreCancelled = true)
    private void onPlayerDropItem(PlayerDropItemEvent e) {
        Item i = e.getItemDrop();
        if (saplings.contains(i.getItemStack().getType()))
            i.setMetadata(metaKS, new FixedMetadataValue(this, e.getPlayer().getUniqueId()));
    }

    // Private classes -------------------------------------------------------------------------------------------------

    private abstract class SART extends BukkitRunnable {
        protected final int       m;
        protected final Item      i;
        protected final ItemStack s;
        protected final Material  t;

        protected int a = 0;

        SART(Item I, Material T, int E, int M, ItemStack S) {
            m = M;
            i = I;
            s = S;
            t = T;
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
        GroundDetect(Item I, Material T, int E, int M, ItemStack S) {
            super(I, T, E, M, S);
        }

        @Override
        protected void proceed() {
            if (i.isOnGround()) {
                cancel();
                if (canPlant()) new Replant(i, t, replantPeriod, replantMaxTry, s);
            }
        }

        private boolean canPlant() {
            Player p = Bukkit.getPlayer((UUID) i.getMetadata(metaKS).get(0).value());
            if (p == null) return false;
            Block           b     = i.getLocation().getBlock();
            BlockPlaceEvent event = new BlockPlaceEvent(b, b.getState(), b.getRelative(BlockFace.DOWN),
                    i.getItemStack(), p, true, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        }
    }

    private class Replant extends SART {
        Replant(Item I, Material T, int E, int M, ItemStack S) {
            super(I, T, E, M, S);
        }

        @Override
        protected void proceed() {
            Block b = i.getLocation().getBlock();

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
            return (ignored.contains(b.getType().name()) && dirtLike.contains(b.getRelative(BlockFace.DOWN).getType()));
        }

        private void plant(Block b) {
            b.setBlockData(datas.get(t));
            int amount = s.getAmount();
            if (amount == 1) i.remove();
            else s.setAmount(amount - 1);
        }
    }
}

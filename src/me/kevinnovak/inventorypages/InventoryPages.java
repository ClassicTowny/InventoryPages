package me.kevinnovak.inventorypages;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map.Entry;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public class InventoryPages extends JavaPlugin implements Listener {
    private HashMap < String, CustomInventory > playerInvs = new HashMap < String, CustomInventory > ();
    ColorConverter colorConv = new ColorConverter(this);

    private ItemStack nextItem, prevItem, noActionItem;
    private Integer prevPos, nextPos;

    // ======================================
    // Enable
    // ======================================
    public void onEnable() {
        saveDefaultConfig();

        Bukkit.getServer().getPluginManager().registerEvents(this, this);

        if (getConfig().getBoolean("metrics")) {
            try {
                MetricsLite metrics = new MetricsLite(this);
                metrics.start();
                Bukkit.getServer().getLogger().info("[InventoryPages] Metrics Enabled!");
            } catch (IOException e) {
                Bukkit.getServer().getLogger().info("[InventoryPages] Failed to Start Metrics.");
            }
        } else {
            Bukkit.getServer().getLogger().info("[InventoryPages] Metrics Disabled.");
        }

        // initialize next, prev items
        initItems();

        // load all online players into hashmap
        for (Player player: Bukkit.getServer().getOnlinePlayers()) {
            try {
                loadInvFromFileIntoHashMap(player);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Bukkit.getServer().getLogger().info("[InventoryPages] Plugin Enabled!");
    }

    // ======================================
    // Disable
    // ======================================
    public void onDisable() {
        for (Player player: Bukkit.getServer().getOnlinePlayers()) {
            // update inventories to hashmap and save to file
            updateInvToHashMap(player);
            saveInvFromHashMapToFile(player);
        }
        Bukkit.getServer().getLogger().info("[InventoryPages] Plugin Disabled!");
    }

    // ======================================
    // Initialize Next Item
    // ======================================
    @SuppressWarnings("deprecation")
    public void initItems() {
        prevItem = new ItemStack(getConfig().getInt("items.prev.ID"), 1, (short) getConfig().getInt("items.prev.variation"));
        ItemMeta prevItemMeta = prevItem.getItemMeta();
        prevItemMeta.setDisplayName(colorConv.convertConfig("items.prev.name"));
        prevItemMeta.setLore(colorConv.convertConfigList("items.prev.lore"));
        prevItem.setItemMeta(prevItemMeta);

        prevPos = getConfig().getInt("items.prev.position");

        nextItem = new ItemStack(getConfig().getInt("items.next.ID"), 1, (short) getConfig().getInt("items.next.variation"));
        ItemMeta nextItemMeta = nextItem.getItemMeta();
        nextItemMeta.setDisplayName(colorConv.convertConfig("items.next.name"));
        nextItemMeta.setLore(colorConv.convertConfigList("items.next.lore"));
        nextItem.setItemMeta(nextItemMeta);

        nextPos = getConfig().getInt("items.next.position");

        noActionItem = new ItemStack(getConfig().getInt("items.noAction.ID"), 1, (short) getConfig().getInt("items.noAction.variation"));
        ItemMeta noActionItemMeta = noActionItem.getItemMeta();
        noActionItemMeta.setDisplayName(colorConv.convertConfig("items.noAction.name"));
        noActionItemMeta.setLore(colorConv.convertConfigList("items.noAction.lore"));
        noActionItem.setItemMeta(noActionItemMeta);
    }

    // ======================================
    // Save Inventory From HashMap To File
    // ======================================
    public void saveInvFromHashMapToFile(Player player) {
        String playerUUID = player.getUniqueId().toString();
        if (playerInvs.containsKey(playerUUID)) {
            File playerFile = new File(getDataFolder() + "/inventories/" + playerUUID.substring(0, 1)  + "/" + playerUUID + ".yml");
            FileConfiguration playerData = YamlConfiguration.loadConfiguration(playerFile);

            // save survival items
            for (Entry < Integer, ArrayList < ItemStack >> pageItemEntry: playerInvs.get(playerUUID).getItems().entrySet()) {
                for (int i = 0; i < pageItemEntry.getValue().size(); i++) {
                	if (pageItemEntry.getValue().get(i) != null) {
                		playerData.set("items.main." + pageItemEntry.getKey() + "." + i, InventoryStringDeSerializer.toBase64(pageItemEntry.getValue().get(i)));
                	} else {
                		playerData.set("items.main." + pageItemEntry.getKey() + "." + i, null);
                	}
                }
            }

            // save creative items
            if (playerInvs.get(playerUUID).hasUsedCreative()) {
                for (int i = 0; i < playerInvs.get(playerUUID).getCreativeItems().size(); i++) {
                	playerData.set("items.creative.0." + i, InventoryStringDeSerializer.toBase64(playerInvs.get(playerUUID).getCreativeItems().get(i)));
                }
            }
            
            try {
                playerData.save(playerFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // ======================================
    // Load Inventory From File Into HashMap
    // ======================================
    public void loadInvFromFileIntoHashMap(Player player) throws IOException {
        int maxPage = 1;
        for (int i = 2; i < 101; i++) {
            if (player.hasPermission("inventorypages.pages." + i)) {
                maxPage = i - 1;
            }
        }

        String playerUUID = player.getUniqueId().toString();
        CustomInventory inventory = new CustomInventory(player, maxPage, prevItem, prevPos, nextItem, nextPos, noActionItem);

        File playerFile = new File(getDataFolder() + "/inventories/" + playerUUID.substring(0, 1)  + "/" + playerUUID + ".yml");
        FileConfiguration playerData = YamlConfiguration.loadConfiguration(playerFile);

        if (playerFile.exists()) {
        	// load survival items
            HashMap < Integer, ArrayList < ItemStack >> pageItemHashMap = new HashMap < Integer, ArrayList < ItemStack >> ();

            for (int i=0; i<maxPage+1; i++) {
	            Bukkit.getLogger().info("Loading " + playerUUID + "'s Page: " + i);
	            ArrayList < ItemStack > pageItems = new ArrayList < ItemStack > (25);
	            for (int j = 0; j < 25; j++) {
	            	ItemStack item = null;
	            	if (playerData.contains("items.main." + i + "." + j)) {
	                	if (playerData.getString("items.main." + i + "." + j) != null) {
	                		item = InventoryStringDeSerializer.stacksFromBase64(playerData.getString("items.main." + i + "." + j))[0];
	                	}
	            	}
	                pageItems.add(item);
	            }
	            pageItemHashMap.put(i, pageItems);
            }

            inventory.setItems(pageItemHashMap);
            
            // load creative items
            if (playerData.contains("items.creative.0")) {
                ArrayList< ItemStack > creativeItems = new ArrayList< ItemStack > (27);
            	for (int i = 0; i < 27; i++) {
            		ItemStack item = InventoryStringDeSerializer.stacksFromBase64(playerData.getString("items.creative.0." + i))[0];
            		creativeItems.add(item);
            	}
            	inventory.setCreativeItems(creativeItems);
            }

        }
        playerInvs.put(playerUUID, inventory);
        playerInvs.get(playerUUID).showPage(0, player.getGameMode());
    }

    // ======================================
    // Update Inventory To HashMap
    // ======================================
    public void updateInvToHashMap(Player player) {
        String playerUUID = player.getUniqueId().toString();
        if (playerInvs.containsKey(playerUUID)) {
            playerInvs.get(playerUUID).saveCurrentPage();
        }
    }

    // ======================================
    // Remove Inventory From HashMap
    // ======================================
    public void removeInvFromHashMap(Player player) {
        String playerUUID = player.getUniqueId().toString();
        if (playerInvs.containsKey(playerUUID)) {
            playerInvs.remove(playerUUID);
        }
    }

    // ======================================
    // Login
    // ======================================
    @EventHandler
    public void playerJoin(PlayerJoinEvent event) throws InterruptedException, IOException {
        Player player = event.getPlayer();
        loadInvFromFileIntoHashMap(player);
    }

    // ======================================
    // Logout
    // ======================================
    @EventHandler
    public void playerQuit(PlayerQuitEvent event) throws InterruptedException {
        Player player = event.getPlayer();
        updateInvToHashMap(player);
        saveInvFromHashMapToFile(player);
        removeInvFromHashMap(player);
    }

    // ======================================
    // Death
    // ======================================
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        //save items before death
        updateInvToHashMap(player);

        List < ItemStack > drops = event.getDrops();
        event.setKeepLevel(true);
        ListIterator < ItemStack > litr = drops.listIterator();
        while (litr.hasNext()) {
            ItemStack stack = litr.next();
            if (stack.getType() == prevItem.getType() && stack.getItemMeta().getDisplayName() == prevItem.getItemMeta().getDisplayName()) {
                litr.remove();
            } else if (stack.getType() == nextItem.getType() && stack.getItemMeta().getDisplayName() == nextItem.getItemMeta().getDisplayName()) {
                litr.remove();
            } else if (stack.getType() == noActionItem.getType() && stack.getItemMeta().getDisplayName() == noActionItem.getItemMeta().getDisplayName()) {
                litr.remove();
            }
        }
    }

    // ======================================
    // Respawn
    // ======================================
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        String playerUUID = player.getUniqueId().toString();
        GameMode gm = player.getGameMode();

        // saves empty inventory (other than next and prev)
        // disable this if you want to keep items
        updateInvToHashMap(player);

        playerInvs.get(playerUUID).showPage(gm);
    }

    // ======================================
    // Inventory Click
    // ======================================
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory eventInv = event.getClickedInventory();
        if (eventInv != null) {
            InventoryType eventInvType = event.getClickedInventory().getType();
            if (eventInvType != null) {
                if (eventInvType == InventoryType.PLAYER) {
                    HumanEntity human = event.getWhoClicked();
                    if (human instanceof Player) {
                        Player player = (Player) human;
                        GameMode gm = player.getGameMode();
                        if (gm != GameMode.CREATIVE) {
                            String playerUUID = (String) player.getUniqueId().toString();
                            int slot = event.getSlot();
                            player.sendMessage("Clicked Slot: " + slot);
                            if (slot == prevPos + 9) {
                                event.setCancelled(true);
                                playerInvs.get(playerUUID).prevPage();
                            } else if (slot == nextPos + 9) {
                                event.setCancelled(true);
                                playerInvs.get(playerUUID).nextPage();
                            }
                        }
                    }
                }
            }
        }
    }

    // ======================================
    // Inventory Pickup
    // ======================================
    @EventHandler
    public void onInventoryPickupItem(InventoryPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (item.getType() == prevItem.getType() && item.getItemMeta().getDisplayName() == prevItem.getItemMeta().getDisplayName()) {
            event.setCancelled(true);
        } else if (item.getType() == nextItem.getType() && item.getItemMeta().getDisplayName() == nextItem.getItemMeta().getDisplayName()) {
            event.setCancelled(true);
        } else if (item.getType() == noActionItem.getType() && item.getItemMeta().getDisplayName() == noActionItem.getItemMeta().getDisplayName()) {
            event.setCancelled(true);
        }
    }

    // ======================================
    // Item Drop
    // ======================================
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (item.getType() == prevItem.getType() && item.getItemMeta().getDisplayName() == prevItem.getItemMeta().getDisplayName()) {
            event.setCancelled(true);
        } else if (item.getType() == nextItem.getType() && item.getItemMeta().getDisplayName() == nextItem.getItemMeta().getDisplayName()) {
            event.setCancelled(true);
        } else if (item.getType() == noActionItem.getType() && item.getItemMeta().getDisplayName() == noActionItem.getItemMeta().getDisplayName()) {
            event.setCancelled(true);
        }
    }

    // ======================================
    // GameMode Change
    // ======================================
    @EventHandler
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        String playerUUID = player.getUniqueId().toString();
        playerInvs.get(playerUUID).saveCurrentPage();
        playerInvs.get(playerUUID).showPage(event.getNewGameMode());
    }
}
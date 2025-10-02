package org.debianrose.deregion;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.block.BlockExplodeEvent;
import cn.nukkit.event.entity.EntityExplodeEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Position;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;
import cn.nukkit.utils.TextFormat;

import java.io.File;
import java.util.*;

public class Main extends PluginBase implements Listener {

    private Config regionsConfig;
    private Map<String, Region> regions;
    private Map<String, RegionSelection> selections;
    private Item regionWand;

    @Override
    public void onEnable() {
        if (!this.getDataFolder().exists()) {
            this.getDataFolder().mkdirs();
        }

        this.regionsConfig = new Config(new File(this.getDataFolder(), "regions.yml"), Config.YAML);
        this.regions = new HashMap<>();
        this.selections = new HashMap<>();

        loadRegions();

        createRegionWand();

        this.getServer().getPluginManager().registerEvents(this, this);

        this.getLogger().info("DeRegion plugin enabled!");
    }

    @Override
    public void onDisable() {
        saveRegions();
        this.getLogger().info("DeRegion plugin disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("region")) {
            return false;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(TextFormat.RED + "This command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("regionprotect.command")) {
            player.sendMessage(TextFormat.RED + "You don't have permission to use this command!");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(TextFormat.RED + "Usage: /region <wand|create|delete|list|addmember|removemember|info> [name] [player]");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "wand":
                giveWand(player);
                break;

            case "create":
                if (args.length < 2) {
                    player.sendMessage(TextFormat.RED + "Usage: /region create <name>");
                    return true;
                }
                createRegion(player, args[1]);
                break;

            case "delete":
                if (args.length < 2) {
                    player.sendMessage(TextFormat.RED + "Usage: /region delete <name>");
                    return true;
                }
                deleteRegion(player, args[1]);
                break;

            case "list":
                listRegions(player);
                break;

            case "addmember":
                if (args.length < 3) {
                    player.sendMessage(TextFormat.RED + "Usage: /region addmember <region> <player>");
                    return true;
                }
                addMember(player, args[1], args[2]);
                break;

            case "removemember":
                if (args.length < 3) {
                    player.sendMessage(TextFormat.RED + "Usage: /region removemember <region> <player>");
                    return true;
                }
                removeMember(player, args[1], args[2]);
                break;

            case "info":
                if (args.length < 2) {
                    player.sendMessage(TextFormat.RED + "Usage: /region info <region>");
                    return true;
                }
                showRegionInfo(player, args[1]);
                break;

            default:
                player.sendMessage(TextFormat.RED + "Usage: /region <wand|create|delete|list|addmember|removemember|info> [name] [player]");
                break;
        }

        return true;
    }

    private void createRegionWand() {
        regionWand = Item.get(Item.WOODEN_AXE);
        regionWand.setCustomName(TextFormat.GOLD + "Region Wand");
        regionWand.setLore("Left-click: Set first position",
                "Right-click: Set second position",
                "Use /region create <name> after selection");
    }

    private void loadRegions() {
        if (regionsConfig.exists("regions")) {
            Map<String, Object> regionsData = regionsConfig.getSection("regions").getAllMap();
            for (Map.Entry<String, Object> entry : regionsData.entrySet()) {
                try {
                    Map<String, Object> regionData = (Map<String, Object>) entry.getValue();
                    Region region = Region.fromMap(regionData);
                    regions.put(entry.getKey(), region);
                } catch (Exception e) {
                    getLogger().error("Error loading region: " + entry.getKey(), e);
                }
            }
        }
        this.getLogger().info("Loaded " + regions.size() + " regions");
    }

    private void saveRegions() {
        Map<String, Object> regionsData = new HashMap<>();
        for (Map.Entry<String, Region> entry : regions.entrySet()) {
            regionsData.put(entry.getKey(), entry.getValue().toMap());
        }
        regionsConfig.set("regions", regionsData);
        regionsConfig.save();
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Item item = event.getItem();

        if (item != null && item.equals(regionWand, true, false)) {
            event.setCancelled(true);

            Block block = event.getBlock();
            Position pos = new Position(block.getX(), block.getY(), block.getZ(), block.getLevel());
            
            String playerName = player.getName();

            RegionSelection selection = selections.getOrDefault(playerName, new RegionSelection());

            if (event.getAction() == PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) {
                selection.setPos1(pos);
                player.sendMessage(TextFormat.GREEN + "First position set to: " + formatPosition(pos));
            } else if (event.getAction() == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
                selection.setPos2(pos);
                player.sendMessage(TextFormat.GREEN + "Second position set to: " + formatPosition(pos));
            }

            selections.put(playerName, selection);

            if (selection.isComplete()) {
                player.sendMessage(TextFormat.YELLOW + "Selection complete! Use /region create <name> to create region.");
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Position pos = new Position(block.getX(), block.getY(), block.getZ(), block.getLevel());
        
        if (!isAllowed(event.getPlayer(), pos)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(TextFormat.RED + "You cannot break blocks in this protected region!");
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        Position pos = new Position(block.getX(), block.getY(), block.getZ(), block.getLevel());
        
        if (!isAllowed(event.getPlayer(), pos)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(TextFormat.RED + "You cannot place blocks in this protected region!");
        }
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        // Защита от взрывов блоков
        List<Block> blocksToRemove = new ArrayList<>();
        for (Block block : event.getBlockList()) {
            Position pos = new Position(block.getX(), block.getY(), block.getZ(), block.getLevel());
            if (isPositionInAnyRegion(pos)) {
                blocksToRemove.add(block);
            }
        }
        event.getBlockList().removeAll(blocksToRemove);
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        // Защита от взрывов сущностей
        List<Block> blocksToRemove = new ArrayList<>();
        for (Block block : event.getBlockList()) {
            Position pos = new Position(block.getX(), block.getY(), block.getZ(), block.getLevel());
            if (isPositionInAnyRegion(pos)) {
                blocksToRemove.add(block);
            }
        }
        event.getBlockList().removeAll(blocksToRemove);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getName());
    }

    private boolean isAllowed(Player player, Position pos) {
        if (player.isOp()) {
            return true;
        }

        for (Region region : regions.values()) {
            if (region.contains(pos) && !region.canBuild(player.getName())) {
                return false;
            }
        }
        return true;
    }

    private boolean isPositionInAnyRegion(Position pos) {
        for (Region region : regions.values()) {
            if (region.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private boolean doesSelectionOverlapWithExistingRegions(Position pos1, Position pos2) {
        for (Region region : regions.values()) {
            if (region.overlapsWith(pos1, pos2)) {
                return true;
            }
        }
        return false;
    }

    private String formatPosition(Position pos) {
        return String.format("(%d, %d, %d)", pos.getFloorX(), pos.getFloorY(), pos.getFloorZ());
    }

    private void giveWand(Player player) {
        player.getInventory().addItem(regionWand);
        player.sendMessage(TextFormat.GREEN + "Region wand added to your inventory!");
    }

    private void createRegion(Player player, String name) {
        if (regions.containsKey(name)) {
            player.sendMessage(TextFormat.RED + "A region with that name already exists!");
            return;
        }

        RegionSelection selection = selections.get(player.getName());
        if (selection == null || !selection.isComplete()) {
            player.sendMessage(TextFormat.RED + "You need to select an area first with the region wand!");
            return;
        }

        // Проверка на пересечение с существующими регионами
        if (doesSelectionOverlapWithExistingRegions(selection.getPos1(), selection.getPos2())) {
            player.sendMessage(TextFormat.RED + "This area overlaps with an existing region! Please choose a different area.");
            return;
        }

        Region region = new Region(name, player.getName(), selection.getPos1(), selection.getPos2());
        regions.put(name, region);
        saveRegions();

        player.sendMessage(TextFormat.GREEN + "Region '" + name + "' created successfully!");
        selections.remove(player.getName());
    }

    private void deleteRegion(Player player, String name) {
        Region region = regions.get(name);
        if (region == null) {
            player.sendMessage(TextFormat.RED + "Region '" + name + "' not found!");
            return;
        }

        if (!region.isOwner(player.getName()) && !player.hasPermission("regionprotect.admin")) {
            player.sendMessage(TextFormat.RED + "You don't own this region!");
            return;
        }

        regions.remove(name);
        saveRegions();
        player.sendMessage(TextFormat.GREEN + "Region '" + name + "' deleted successfully!");
    }

    private void listRegions(Player player) {
        if (regions.isEmpty()) {
            player.sendMessage(TextFormat.YELLOW + "No regions defined.");
            return;
        }

        player.sendMessage(TextFormat.GOLD + "=== Regions ===");
        for (Region region : regions.values()) {
            String ownerText = region.isOwner(player.getName()) ? TextFormat.GREEN + " (Yours)" : "";
            player.sendMessage(TextFormat.WHITE + "- " + region.getName() + ownerText);
        }
    }

    private void addMember(Player player, String regionName, String memberName) {
        Region region = regions.get(regionName);
        if (region == null) {
            player.sendMessage(TextFormat.RED + "Region '" + regionName + "' not found!");
            return;
        }

        if (!region.isOwner(player.getName()) && !player.hasPermission("regionprotect.admin")) {
            player.sendMessage(TextFormat.RED + "You don't own this region!");
            return;
        }

        region.addMember(memberName);
        saveRegions();
        player.sendMessage(TextFormat.GREEN + "Player '" + memberName + "' added to region '" + regionName + "'!");
    }

    private void removeMember(Player player, String regionName, String memberName) {
        Region region = regions.get(regionName);
        if (region == null) {
            player.sendMessage(TextFormat.RED + "Region '" + regionName + "' not found!");
            return;
        }

        if (!region.isOwner(player.getName()) && !player.hasPermission("regionprotect.admin")) {
            player.sendMessage(TextFormat.RED + "You don't own this region!");
            return;
        }

        region.removeMember(memberName);
        saveRegions();
        player.sendMessage(TextFormat.GREEN + "Player '" + memberName + "' removed from region '" + regionName + "'!");
    }

    private void showRegionInfo(Player player, String regionName) {
        Region region = regions.get(regionName);
        if (region == null) {
            player.sendMessage(TextFormat.RED + "Region '" + regionName + "' not found!");
            return;
        }

        player.sendMessage(TextFormat.GOLD + "=== Region Info: " + regionName + " ===");
        player.sendMessage(TextFormat.WHITE + "Owner: " + TextFormat.YELLOW + region.getOwner());
        player.sendMessage(TextFormat.WHITE + "Position 1: " + TextFormat.YELLOW + formatPosition(region.getPos1()));
        player.sendMessage(TextFormat.WHITE + "Position 2: " + TextFormat.YELLOW + formatPosition(region.getPos2()));
        
        List<String> members = region.getMembers();
        if (members.isEmpty()) {
            player.sendMessage(TextFormat.WHITE + "Members: " + TextFormat.YELLOW + "None");
        } else {
            player.sendMessage(TextFormat.WHITE + "Members: " + TextFormat.YELLOW + String.join(", ", members));
        }
    }

    public static class Region {
        private String name;
        private String owner;
        private Position pos1;
        private Position pos2;
        private Set<String> members;

        public Region(String name, String owner, Position pos1, Position pos2) {
            this.name = name;
            this.owner = owner;
            this.pos1 = pos1;
            this.pos2 = pos2;
            this.members = new HashSet<>();
        }

        public boolean contains(Position pos) {
            int minX = Math.min(pos1.getFloorX(), pos2.getFloorX());
            int maxX = Math.max(pos1.getFloorX(), pos2.getFloorX());
            int minY = Math.min(pos1.getFloorY(), pos2.getFloorY());
            int maxY = Math.max(pos1.getFloorY(), pos2.getFloorY());
            int minZ = Math.min(pos1.getFloorZ(), pos2.getFloorZ());
            int maxZ = Math.max(pos1.getFloorZ(), pos2.getFloorZ());

            int x = pos.getFloorX();
            int y = pos.getFloorY();
            int z = pos.getFloorZ();

            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        public boolean overlapsWith(Position otherPos1, Position otherPos2) {
            int thisMinX = Math.min(pos1.getFloorX(), pos2.getFloorX());
            int thisMaxX = Math.max(pos1.getFloorX(), pos2.getFloorX());
            int thisMinY = Math.min(pos1.getFloorY(), pos2.getFloorY());
            int thisMaxY = Math.max(pos1.getFloorY(), pos2.getFloorY());
            int thisMinZ = Math.min(pos1.getFloorZ(), pos2.getFloorZ());
            int thisMaxZ = Math.max(pos1.getFloorZ(), pos2.getFloorZ());

            int otherMinX = Math.min(otherPos1.getFloorX(), otherPos2.getFloorX());
            int otherMaxX = Math.max(otherPos1.getFloorX(), otherPos2.getFloorX());
            int otherMinY = Math.min(otherPos1.getFloorY(), otherPos2.getFloorY());
            int otherMaxY = Math.max(otherPos1.getFloorY(), otherPos2.getFloorY());
            int otherMinZ = Math.min(otherPos1.getFloorZ(), otherPos2.getFloorZ());
            int otherMaxZ = Math.max(otherPos1.getFloorZ(), otherPos2.getFloorZ());

            return (thisMinX <= otherMaxX && thisMaxX >= otherMinX) &&
                   (thisMinY <= otherMaxY && thisMaxY >= otherMinY) &&
                   (thisMinZ <= otherMaxZ && thisMaxZ >= otherMinZ);
        }

        public boolean canBuild(String playerName) {
            return isOwner(playerName) || members.contains(playerName);
        }

        public boolean isOwner(String playerName) {
            return owner.equals(playerName);
        }

        public void addMember(String playerName) {
            members.add(playerName);
        }

        public void removeMember(String playerName) {
            members.remove(playerName);
        }

        public List<String> getMembers() {
            return new ArrayList<>(members);
        }

        public String getName() {
            return name;
        }

        public String getOwner() {
            return owner;
        }

        public Position getPos1() {
            return pos1;
        }

        public Position getPos2() {
            return pos2;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("name", name);
            map.put("owner", owner);
            map.put("pos1", pos1ToString());
            map.put("pos2", pos2ToString());
            map.put("level", pos1.getLevel().getName());
            map.put("members", new ArrayList<>(members));
            return map;
        }

        public static Region fromMap(Map<String, Object> map) {
            String name = (String) map.get("name");
            String owner = (String) map.get("owner");
            String levelName = (String) map.get("level");

            cn.nukkit.level.Level level = cn.nukkit.Server.getInstance().getLevelByName(levelName);
            if (level == null) {
                throw new IllegalArgumentException("Level not found: " + levelName);
            }

            Position pos1 = stringToPosition((String) map.get("pos1"), level);
            Position pos2 = stringToPosition((String) map.get("pos2"), level);

            Region region = new Region(name, owner, pos1, pos2);
            
            // Загрузка списка участников
            if (map.containsKey("members")) {
                List<String> memberList = (List<String>) map.get("members");
                region.members.addAll(memberList);
            }

            return region;
        }

        private String pos1ToString() {
            return String.format("%d,%d,%d", pos1.getFloorX(), pos1.getFloorY(), pos1.getFloorZ());
        }

        private String pos2ToString() {
            return String.format("%d,%d,%d", pos2.getFloorX(), pos2.getFloorY(), pos2.getFloorZ());
        }

        private static Position stringToPosition(String str, cn.nukkit.level.Level level) {
            String[] parts = str.split(",");
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            return new Position(x, y, z, level);
        }
    }

    public static class RegionSelection {
        private Position pos1;
        private Position pos2;

        public void setPos1(Position pos1) {
            this.pos1 = pos1;
        }

        public void setPos2(Position pos2) {
            this.pos2 = pos2;
        }

        public boolean isComplete() {
            return pos1 != null && pos2 != null;
        }

        public Position getPos1() {
            return pos1;
        }

        public Position getPos2() {
            return pos2;
        }
    }
}

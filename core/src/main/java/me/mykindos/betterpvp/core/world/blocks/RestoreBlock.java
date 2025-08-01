package me.mykindos.betterpvp.core.world.blocks;

import lombok.Data;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;


@Data
public class RestoreBlock {

    private final Block block;
    private Material newMaterial;
    private long expire;

    private BlockData blockData;
    private int blockLevel;

    @Nullable
    private LivingEntity summoner;
    @Nullable
    private final String label;

    private boolean restored;

    public RestoreBlock(Block block, BlockData blockData, Material newMaterial, long expire, @Nullable LivingEntity summoner, @Nullable String label) {
        this.block = block;
        this.newMaterial = newMaterial;
        this.expire = System.currentTimeMillis() + expire;
        this.blockData = blockData;
        this.summoner = summoner;
        this.label = label;

        block.setType(newMaterial);
    }

    public RestoreBlock(Block block, Material newMaterial, long expire, @Nullable LivingEntity summoner, @Nullable String label) {
        this(block, block.getBlockData().clone(), newMaterial, expire, summoner, label);
    }

    public RestoreBlock(Block block, Material newMaterial, long expire, @Nullable LivingEntity summoner) {
        this(block, newMaterial, expire, summoner, null);
    }

    public void restore() {
        if (!block.getBlockData().equals(blockData)) {
            block.setBlockData(blockData, blockData instanceof Levelled); // Only apply physics to water so flows are not broken
        }

        restored = true;
        // Update nearby blocks
        UtilServer.runTaskLater(JavaPlugin.getPlugin(Core.class), () -> block.getState().update(false, true), 1L);
    }

}

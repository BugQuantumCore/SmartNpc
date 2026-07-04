package com.pla.player_npc.spawnhandler;

import com.pla.player_npc.entity.PlayerNpcEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class ExampleSpawnData extends SavedData {
    public static final String ID = "player_npc_singleton";
    private UUID activeId = null;
    private long claimTick = 0L;
    private static final long COOLDOWN_TICKS = 20L * 60L * 10L;

    public static ExampleSpawnData get(ServerLevel serverLevel) {
        return serverLevel.getDataStorage().computeIfAbsent(ExampleSpawnData::load, ExampleSpawnData::new, ID);
    }

    public static ExampleSpawnData load(CompoundTag compoundTag) {
        ExampleSpawnData alexData = new ExampleSpawnData();
        if (compoundTag.hasUUID("activeId")) {
            alexData.activeId = compoundTag.getUUID("activeId");
        }
        if (compoundTag.contains("claimTick", Tag.TAG_LONG)) {
            alexData.claimTick = compoundTag.getLong("claimTick");
        }
        return alexData;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag compoundTag) {
        if (activeId != null) {
            compoundTag.putUUID("activeId", activeId);
        }
        compoundTag.putLong("claimTick", claimTick);
        return compoundTag;
    }

    private static long now(ServerLevel level) {
        return level.getGameTime();
    }

    public boolean isOccupied(ServerLevel serverLevel) {
        if (activeId != null) {
            Entity entity = serverLevel.getEntity(activeId);
            if (entity instanceof PlayerNpcEntity && entity.isAlive()) {
                return true;
            } else {
                activeId = null;
                setDirty();
                return false;
            }
        }
        if (claimTick <= 0L) return false;
        long elapsed = now(serverLevel) - claimTick;
        return elapsed < COOLDOWN_TICKS;
    }

    public boolean tryClaim(ServerLevel serverLevel, UUID id) {
        if (isOccupied(serverLevel)) {
            return false;
        }
        activeId = id;
        claimTick = now(serverLevel);
        setDirty();
        return true;
    }

    public void releaseIfMatches(ServerLevel serverLevel, UUID id) {
        if (activeId != null && activeId.equals(id)) {
            activeId = null;
            claimTick = now(serverLevel);
            setDirty();
        }
    }
}

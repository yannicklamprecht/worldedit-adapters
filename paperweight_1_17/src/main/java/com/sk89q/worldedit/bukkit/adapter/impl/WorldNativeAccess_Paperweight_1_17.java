package com.sk89q.worldedit.bukkit.adapter.impl;

import java.lang.ref.WeakReference;
import java.util.Objects;

import javax.annotation.Nullable;

import com.sk89q.worldedit.world.storage.ChunkStore;
import org.bukkit.craftbukkit.v1_17_R1.CraftWorld;
import org.bukkit.craftbukkit.v1_17_R1.block.data.CraftBlockData;
import org.bukkit.event.block.BlockPhysicsEvent;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.internal.block.BlockStateIdAccess;
import com.sk89q.worldedit.internal.wna.WorldNativeAccess;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.util.nbt.CompoundBinaryTag;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

public class WorldNativeAccess_Paperweight_1_17 implements
    WorldNativeAccess<LevelChunk, BlockState, BlockPos> {

  private static final int UPDATE = 1, NOTIFY = 2;

  private final Spigot_Paperweight_1_17 adapter;
  private final WeakReference<Level> world;
  private SideEffectSet sideEffectSet;

  public WorldNativeAccess_Paperweight_1_17(Spigot_Paperweight_1_17 adapter, WeakReference<Level> world) {
    this.adapter = adapter;
    this.world = world;
  }

  private Level getWorld() {
    return Objects.requireNonNull(world.get(), "The reference to the world was lost");
  }

  @Override
  public void setCurrentSideEffectSet(SideEffectSet sideEffectSet) {
    this.sideEffectSet = sideEffectSet;
  }

  @Override
  public LevelChunk getChunk(int x, int z) {
    return getWorld().getChunk(x, z);
  }

  @Override
  public BlockState toNative(com.sk89q.worldedit.world.block.BlockState state) {
    int stateId = BlockStateIdAccess.getBlockStateId(state);
    return BlockStateIdAccess.isValidInternalId(stateId)
        ? Block.stateById(stateId)
        : ((CraftBlockData) BukkitAdapter.adapt(state)).getState();
  }

  @Override
  public BlockState getBlockState(LevelChunk chunk, BlockPos position) {
    return chunk.getTypeIfLoaded(position);
  }

  @Nullable
  @Override
  public BlockState setBlockState(LevelChunk chunk, BlockPos position, BlockState state) {
    return chunk.setBlockState(position, state, false);
  }

  @Override
  public BlockState getValidBlockForPosition(BlockState block, BlockPos position) {
    return Block.updateFromNeighbourShapes(block, getWorld(), position);
  }

  @Override
  public BlockPos getPosition(int x, int y, int z) {
    return new BlockPos(x, y, z);
  }

  @Override
  public void updateLightingForBlock(BlockPos position) {
    getWorld().getLightEngine().checkBlock(position);
  }

  @Override
  public boolean updateTileEntity(BlockPos position, CompoundBinaryTag tag) {
    // We will assume that the tile entity was created for us,
    // though we do not do this on the other versions
    BlockEntity tileEntity = getWorld().getBlockEntity(position);
    if (tileEntity == null) {
      return false;
    }
    Tag nativeTag = adapter.fromNative(tag);
    Spigot_Paperweight_1_17.readTagIntoTileEntity((net.minecraft.nbt.CompoundTag) nativeTag, tileEntity);
    return true;
  }

    @Override
    public void notifyBlockUpdate(LevelChunk chunk, BlockPos position, BlockState oldState, BlockState newState) {
        if (chunk.getSections()[position.getY() >> ChunkStore.CHUNK_SHIFTS] != null) {
            getWorld().sendBlockUpdated(position, oldState, newState, UPDATE | NOTIFY);
        }
    }

  @Override
  public boolean isChunkTicking(LevelChunk chunk) {
    return chunk.getFullStatus().isOrAfter(ChunkHolder.FullChunkStatus.TICKING);
  }

    @Override
    public void markBlockChanged(LevelChunk chunk, BlockPos position) {
        if (chunk.getSections()[position.getY() >> ChunkStore.CHUNK_SHIFTS] != null) {
            ((net.minecraft.server.level.ServerChunkCache) getWorld().getChunkSource())
                    .blockChanged(position);
        }
    }

  private static final Direction[] NEIGHBOUR_ORDER = {
      Direction.WEST, Direction.EAST,
      Direction.DOWN, Direction.UP,
      Direction.NORTH, Direction.SOUTH
  };

  @Override
  public void notifyNeighbors(BlockPos pos, BlockState oldState, BlockState newState) {
    Level world = getWorld();
    if (sideEffectSet.shouldApply(SideEffect.EVENTS)) {
      world.blockUpdated(pos, oldState.getBlock());
    } else {
      // When we don't want events, manually run the physics without them.
      // Un-nest neighbour updating
      for (Direction direction : NEIGHBOUR_ORDER) {
        BlockPos shifted = pos.relative(direction);
        world.getBlockState(shifted)
            .neighborChanged(world, shifted, oldState.getBlock(), pos, false);
      }
    }
    if (newState.hasAnalogOutputSignal()) {
      world.blockUpdated(pos, newState.getBlock());
    }
  }

  @Override
  public void updateNeighbors(BlockPos pos, BlockState oldState, BlockState newState,
      int recursionLimit) {
    Level world = getWorld();
    // a == updateNeighbors
    // b == updateDiagonalNeighbors
    oldState.updateIndirectNeighbourShapes(world, pos, NOTIFY);
    if (sideEffectSet.shouldApply(SideEffect.EVENTS)) {
      CraftWorld craftWorld = world.getWorld();
      if (craftWorld != null) {
        BlockPhysicsEvent event = new BlockPhysicsEvent(
            craftWorld.getBlockAt(pos.getX(), pos.getY(), pos.getZ()),
            CraftBlockData.fromData(newState));
        world.getCraftServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
          return;
        }
      }
    }
    newState.updateNeighbourShapes(world, pos, NOTIFY);
    newState.updateIndirectNeighbourShapes(world, pos, NOTIFY);
  }

  @Override
  public void onBlockStateChange(BlockPos pos, BlockState oldState, BlockState newState) {
    getWorld().onBlockStateChange(pos, oldState, newState);
  }
}

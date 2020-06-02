/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.bukkit.adapter.impl;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.generator.ChunkGenerator;
import org.spigotmc.SpigotConfig;
import org.spigotmc.WatchdogThread;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseItem;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.adapter.BukkitImplAdapter;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.extension.platform.Watchdog;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.internal.Constants;
import com.sk89q.worldedit.internal.block.BlockStateIdAccess;
import com.sk89q.worldedit.internal.wna.WorldNativeAccess;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.registry.state.BooleanProperty;
import com.sk89q.worldedit.registry.state.DirectionalProperty;
import com.sk89q.worldedit.registry.state.EnumProperty;
import com.sk89q.worldedit.registry.state.IntegerProperty;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent;
import com.sk89q.worldedit.util.io.file.SafeFiles;
import com.sk89q.worldedit.util.nbt.BinaryTag;
import com.sk89q.worldedit.util.nbt.ByteArrayBinaryTag;
import com.sk89q.worldedit.util.nbt.ByteBinaryTag;
import com.sk89q.worldedit.util.nbt.CompoundBinaryTag;
import com.sk89q.worldedit.util.nbt.DoubleBinaryTag;
import com.sk89q.worldedit.util.nbt.EndBinaryTag;
import com.sk89q.worldedit.util.nbt.FloatBinaryTag;
import com.sk89q.worldedit.util.nbt.IntArrayBinaryTag;
import com.sk89q.worldedit.util.nbt.IntBinaryTag;
import com.sk89q.worldedit.util.nbt.ListBinaryTag;
import com.sk89q.worldedit.util.nbt.LongArrayBinaryTag;
import com.sk89q.worldedit.util.nbt.LongBinaryTag;
import com.sk89q.worldedit.util.nbt.ShortBinaryTag;
import com.sk89q.worldedit.util.nbt.StringBinaryTag;
import com.sk89q.worldedit.world.DataFixer;
import com.sk89q.worldedit.world.RegenOptions;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.item.ItemType;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryReadOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder.Failure;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkBiomeContainer;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.WorldData;

public final class Spigot_Toothpick implements BukkitImplAdapter {

    private final Logger logger = Logger.getLogger(getClass().getCanonicalName());

    private final Field nbtListTagListField;
    private final Field serverWorldsField;
    private final Watchdog watchdog;

    private final Method getChunkFutureMethod;
    private final Field chunkProviderExecutorField;

    // ------------------------------------------------------------------------
    // Code that may break between versions of Minecraft
    // ------------------------------------------------------------------------

    public Spigot_Toothpick() throws NoSuchFieldException, NoSuchMethodException {
        // A simple test
        CraftServer.class.cast(Bukkit.getServer());


        int dataVersion = CraftMagicNumbers.INSTANCE.getDataVersion();
        if (dataVersion != 2230) throw new UnsupportedClassVersionError("Not 1.15.2!");

        // The list of tags on an NBTTagList
        nbtListTagListField = net.minecraft.nbt.ListTag.class.getDeclaredField("list");
        nbtListTagListField.setAccessible(true);

        serverWorldsField = CraftServer.class.getDeclaredField("worlds");
        serverWorldsField.setAccessible(true);

        getChunkFutureMethod = net.minecraft.server.level.ServerChunkCache.class.getDeclaredMethod("getChunkFutureMainThread",
            int.class, int.class, ChunkStatus.class, boolean.class);
        getChunkFutureMethod.setAccessible(true);

        chunkProviderExecutorField = net.minecraft.server.level.ServerChunkCache.class.getDeclaredField("mainThreadProcessor");
        chunkProviderExecutorField.setAccessible(true);

        new DataConverters_Toothpick(dataVersion, this).build(ForkJoinPool.commonPool());

        Watchdog watchdog;
        try {
            Class.forName("org.spigotmc.WatchdogThread");
            watchdog = new SpigotWatchdog();
        } catch (ClassNotFoundException e) {
            try {
                watchdog = new MojangWatchdog(((CraftServer) Bukkit.getServer()).getServer());
            } catch (NoSuchFieldException ex) {
                watchdog = null;
            }
        }
        this.watchdog = watchdog;

        try {
            Class.forName("org.spigotmc.SpigotConfig");
            SpigotConfig.config.set("world-settings.worldeditregentempworld.verbose", false);
        } catch (ClassNotFoundException ignored) {}
    }

    @Override
    public DataFixer getDataFixer() {
        return DataConverters_Toothpick.INSTANCE;
    }

    /**
     * Read the given NBT data into the given tile entity.
     *
     * @param tileEntity the tile entity
     * @param tag the tag
     */
    static void readTagIntoTileEntity(net.minecraft.nbt.CompoundTag tag, net.minecraft.world.level.block.entity.BlockEntity tileEntity) {
        tileEntity.load(tileEntity.getBlockState(), tag);
    }

    /**
     * Write the tile entity's NBT data to the given tag.
     *
     * @param tileEntity the tile entity
     * @param tag the tag
     */
    private static void readTileEntityIntoTag(net.minecraft.world.level.block.entity.BlockEntity tileEntity, net.minecraft.nbt.CompoundTag tag) {
        tileEntity.save(tag);
    }

    /**
     * Get the ID string of the given entity.
     *
     * @param entity the entity
     * @return the entity ID or null if one is not known
     */
    @Nullable
    private static String getEntityId(net.minecraft.world.entity.Entity entity) {
        ResourceLocation minecraftkey = net.minecraft.world.entity.EntityType.getKey(entity.getType());

        return minecraftkey == null ? null : minecraftkey.toString();
    }

    /**
     * Create an entity using the given entity ID.
     *
     * @param id the entity ID
     * @param world the world
     * @return an entity or null
     */
    @Nullable
    private static net.minecraft.world.entity.Entity createEntityFromId(String id, net.minecraft.world.level.Level world) {
        return net.minecraft.world.entity.EntityType.byString(id).map(t -> t.create(world)).orElse(null);
    }

    /**
     * Write the given NBT data into the given entity.
     *
     * @param entity the entity
     * @param tag the tag
     */
    private static void readTagIntoEntity(net.minecraft.nbt.CompoundTag tag, net.minecraft.world.entity.Entity entity) {
        entity.load(tag);
    }

    /**
     * Write the entity's NBT data to the given tag.
     *
     * @param entity the entity
     * @param tag the tag
     */
    private static void readEntityIntoTag(net.minecraft.world.entity.Entity entity, net.minecraft.nbt.CompoundTag tag) {
        entity.save(tag);
    }

    private static net.minecraft.world.level.block.Block getBlockFromType(BlockType blockType) {
        return Registry.BLOCK.get(net.minecraft.resources.ResourceLocation.tryParse(blockType.getId()));
    }

    private static net.minecraft.world.item.Item getItemFromType(ItemType itemType) {
        return Registry.ITEM.get(net.minecraft.resources.ResourceLocation.tryParse(itemType.getId()));
    }

    @Override
    public OptionalInt getInternalBlockStateId(BlockData data) {
        net.minecraft.world.level.block.state.BlockState state = ((CraftBlockData) data).getState();
        int combinedId = net.minecraft.world.level.block.Block.getId(state);
        return combinedId == 0 && state.getBlock() != net.minecraft.world.level.block.Blocks.AIR ? OptionalInt.empty() : OptionalInt.of(combinedId);
    }

    @Override
    public OptionalInt getInternalBlockStateId(BlockState state) {
        net.minecraft.world.level.block.Block mcBlock = getBlockFromType(state.getBlockType());
        net.minecraft.world.level.block.state.BlockState newState = mcBlock.stateDefinition.any();
        Map<Property<?>, Object> states = state.getStates();
        newState = applyProperties(mcBlock.getStateDefinition(), newState, states);
        final int combinedId = net.minecraft.world.level.block.Block.getId(newState);
        return combinedId == 0 && state.getBlockType() != BlockTypes.AIR ? OptionalInt.empty() : OptionalInt.of(combinedId);
    }

    @Override
    public BaseBlock getBlock(Location location) {
        checkNotNull(location);

        CraftWorld craftWorld = ((CraftWorld) location.getWorld());
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        final ServerLevel handle = craftWorld.getHandle();
        LevelChunk chunk = handle.getChunk(x >> 4, z >> 4);
        final BlockPos blockPos = new net.minecraft.core.BlockPos(x, y, z);
        final net.minecraft.world.level.block.state.BlockState blockData = chunk.getBlockData(blockPos);
        int internalId = net.minecraft.world.level.block.Block.getId(blockData);
        BlockState state = BlockStateIdAccess.getBlockStateById(internalId);
        if (state == null) {
            org.bukkit.block.Block bukkitBlock = location.getBlock();
            state = BukkitAdapter.adapt(bukkitBlock.getBlockData());
        }

        // Read the NBT data
        BlockEntity te = chunk.getBlockEntity(blockPos, LevelChunk.EntityCreationType.CHECK);
        if (te != null) {
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            readTileEntityIntoTag(te, tag); // Load data
            return state.toBaseBlock(LazyReference.computed((CompoundBinaryTag)toNative(tag)));
        }

        return state.toBaseBlock();
    }

    @Override
    public WorldNativeAccess<?, ?, ?> createWorldNativeAccess(org.bukkit.World world) {
        return new WorldNativeAccess_Toothpick(this,
            new WeakReference<>(((CraftWorld) world).getHandle()));
    }

    private static net.minecraft.core.Direction adapt(Direction face) {
        switch (face) {
            case NORTH: return net.minecraft.core.Direction.NORTH;
            case SOUTH: return net.minecraft.core.Direction.SOUTH;
            case WEST: return net.minecraft.core.Direction.WEST;
            case EAST: return net.minecraft.core.Direction.EAST;
            case DOWN: return net.minecraft.core.Direction.DOWN;
            case UP:
            default:
                return net.minecraft.core.Direction.UP;
        }
    }

    private net.minecraft.world.level.block.state.BlockState applyProperties(net.minecraft.world.level.block.state.StateDefinition<net.minecraft.world.level.block.Block, net.minecraft.world.level.block.state.BlockState> stateContainer, net.minecraft.world.level.block.state.BlockState newState, Map<Property<?>, Object> states) {
        for (Map.Entry<Property<?>, Object> state : states.entrySet()) {
            net.minecraft.world.level.block.state.properties.Property<?> property = stateContainer.getProperty(state.getKey().getName());
            Comparable<?> value = (Comparable<?>) state.getValue();
            // we may need to adapt this value, depending on the source prop
            if (property instanceof net.minecraft.world.level.block.state.properties.DirectionProperty) {
                Direction dir = (Direction) value;
                value = adapt(dir);
            } else if (property instanceof net.minecraft.world.level.block.state.properties.EnumProperty) {
                String enumName = (String) value;
                value = ((net.minecraft.world.level.block.state.properties.EnumProperty<?>) property).getValue((String) value).orElseGet(() -> {
                    throw new IllegalStateException("Enum property " + property.getName() + " does not contain " + enumName);
                });
            }

            newState = newState.setValue((net.minecraft.world.level.block.state.properties.Property)property, (Comparable) value);
        }
        return newState;
    }

    @Override
    public BaseEntity getEntity(org.bukkit.entity.Entity entity) {
        checkNotNull(entity);

        CraftEntity craftEntity = ((CraftEntity) entity);
        net.minecraft.world.entity.Entity mcEntity = craftEntity.getHandle();

        String id = getEntityId(mcEntity);

        if (id != null) {
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            readEntityIntoTag(mcEntity, tag);
            return new BaseEntity(com.sk89q.worldedit.world.entity.EntityTypes.get(id),  LazyReference.computed((CompoundBinaryTag)toNative(tag)));
        } else {
            return null;
        }
    }

    @Nullable
    @Override
    public org.bukkit.entity.Entity createEntity(Location location, BaseEntity state) {
        checkNotNull(location);
        checkNotNull(state);

        CraftWorld craftWorld = ((CraftWorld) location.getWorld());
        ServerLevel worldServer = craftWorld.getHandle();

        Entity createdEntity = createEntityFromId(state.getType().getId(), craftWorld.getHandle());

        if (createdEntity != null) {
            CompoundBinaryTag nativeTag = state.getNbt();
            if (nativeTag != null) {
                net.minecraft.nbt.CompoundTag tag = (net.minecraft.nbt.CompoundTag) fromNative(nativeTag);
                for (String name : Constants.NO_COPY_ENTITY_NBT_FIELDS) {
                    tag.remove(name);
                }
                readTagIntoEntity(tag, createdEntity);
            }

            createdEntity.absMoveTo(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());

            worldServer.addEntity(createdEntity, SpawnReason.CUSTOM);
            return createdEntity.getBukkitEntity();
        } else {
            return null;
        }
    }

    @Override
    public Component getRichBlockName(BlockType blockType) {
        return TranslatableComponent.of(getBlockFromType(blockType).getDescriptionId());
    }

    @Override
    public Component getRichItemName(ItemType itemType) {
        return TranslatableComponent.of(getItemFromType(itemType).getDescriptionId());
    }

    @Override
    public Component getRichItemName(BaseItemStack itemStack) {
        return TranslatableComponent.of(CraftItemStack.asNMSCopy(BukkitAdapter.adapt(itemStack)).getDescriptionId());
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, ? extends Property<?>> getProperties(BlockType blockType) {
        Map<String, Property<?>> properties = Maps.newTreeMap(String::compareTo);
        Block block = getBlockFromType(blockType);
        StateDefinition<Block, net.minecraft.world.level.block.state.BlockState> blockStateList = block.getStateDefinition();
        for (net.minecraft.world.level.block.state.properties.Property state : blockStateList.getProperties()) {
            Property property;
            if (state instanceof net.minecraft.world.level.block.state.properties.BooleanProperty) {
                property = new BooleanProperty(state.getName(), ImmutableList.copyOf(state.getPossibleValues()));
            } else if (state instanceof net.minecraft.world.level.block.state.properties.DirectionProperty) {
                property = new DirectionalProperty(state.getName(),
                        (List<Direction>) state.getPossibleValues().stream().map(e -> Direction.valueOf(((StringRepresentable) e).getSerializedName().toUpperCase())).collect(Collectors.toList()));
            } else if (state instanceof net.minecraft.world.level.block.state.properties.EnumProperty) {
                property = new EnumProperty(state.getName(),
                        (List<String>) state.getPossibleValues().stream().map(e -> ((StringRepresentable) e).getSerializedName()).collect(Collectors.toList()));
            } else if (state instanceof net.minecraft.world.level.block.state.properties.IntegerProperty) {
                property = new IntegerProperty(state.getName(), ImmutableList.copyOf(state.getPossibleValues()));
            } else {
                throw new IllegalArgumentException("WorldEdit needs an update to support " + state.getClass().getSimpleName());
            }

            properties.put(property.getName(), property);
        }
        return properties;
    }

    @Override
    public void sendFakeNBT(Player player, BlockVector3 pos, CompoundBinaryTag nbtData) {
        ((CraftPlayer) player).getHandle().connection.send(new net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket(
            new BlockPos(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ()),
            7,
            (net.minecraft.nbt.CompoundTag) fromNative(nbtData)
        ));
    }

    @Override
    public void sendFakeOP(Player player) {
        ((CraftPlayer) player).getHandle().connection.send(new net.minecraft.network.protocol.game.ClientboundEntityEventPacket(
                ((CraftPlayer) player).getHandle(), (byte) 28
        ));
    }

    @Override
    public org.bukkit.inventory.ItemStack adapt(BaseItemStack item) {
        ItemStack stack = new ItemStack(Registry.ITEM.get(ResourceLocation.tryParse(item.getType().getId())), item.getAmount());
        stack.setTag(((net.minecraft.nbt.CompoundTag) fromNative(item.getNbt())));
        return CraftItemStack.asCraftMirror(stack);
    }

    @Override
    public BaseItemStack adapt(org.bukkit.inventory.ItemStack itemStack) {
        final ItemStack nmsStack = CraftItemStack.asNMSCopy(itemStack);
        final BaseItemStack weStack = new BaseItemStack(BukkitAdapter.asItemType(itemStack.getType()), itemStack.getAmount());
        weStack.setNbt(((CompoundBinaryTag) toNative(nmsStack.getTag())));
        return weStack;
    }

    private final LoadingCache<ServerLevel, FakePlayer_Toothpick> fakePlayers
            = CacheBuilder.newBuilder().weakKeys().softValues().build(CacheLoader.from(FakePlayer_Toothpick::new));

    @Override
    public boolean simulateItemUse(org.bukkit.World world, BlockVector3 position, BaseItem item, Direction face) {
        CraftWorld craftWorld = (CraftWorld) world;
        ServerLevel worldServer = craftWorld.getHandle();
        ItemStack stack = CraftItemStack.asNMSCopy(BukkitAdapter.adapt(item instanceof BaseItemStack
                ? ((BaseItemStack) item) : new BaseItemStack(item.getType(), LazyReference.from(item::getNbt), 1)));
        stack.setTag((net.minecraft.nbt.CompoundTag) fromNative(item.getNbt()));

        FakePlayer_Toothpick fakePlayer;
        try {
            fakePlayer = fakePlayers.get(worldServer);
        } catch (ExecutionException ignored) {
            return false;
        }
        fakePlayer.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
        fakePlayer.forceSetPositionRotation(position.getBlockX(), position.getBlockY(), position.getBlockZ(),
                (float) face.toVector().toYaw(), (float) face.toVector().toPitch());

        final BlockPos blockPos = new BlockPos(position.getBlockX(), position.getBlockY(), position.getBlockZ());
        final net.minecraft.world.phys.Vec3 blockVec = new net.minecraft.world.phys.Vec3(blockPos.x, blockPos.y, blockPos.z);
        final net.minecraft.core.Direction enumFacing = adapt(face);
        net.minecraft.world.phys.BlockHitResult rayTrace = new net.minecraft.world.phys.BlockHitResult(blockVec, enumFacing, blockPos, false);
        net.minecraft.world.item.context.UseOnContext context = new net.minecraft.world.item.context.UseOnContext(fakePlayer, net.minecraft.world.InteractionHand.MAIN_HAND, rayTrace);
        InteractionResult result = stack.placeItem(context, net.minecraft.world.InteractionHand.MAIN_HAND);
        if (result != InteractionResult.SUCCESS) {
            if (worldServer.getBlockState(blockPos).use(worldServer, fakePlayer, net.minecraft.world.InteractionHand.MAIN_HAND, rayTrace).consumesAction()) {
                result = InteractionResult.SUCCESS;
            } else {
                result = stack.getItem().use(worldServer, fakePlayer, net.minecraft.world.InteractionHand.MAIN_HAND).getResult();
            }
        }

        return result == InteractionResult.SUCCESS;
    }

    @Override
    public boolean canPlaceAt(World world, BlockVector3 position, BlockState blockState) {
        int internalId = BlockStateIdAccess.getBlockStateId(blockState);
        net.minecraft.world.level.block.state.BlockState blockData = Block.stateById(internalId);
        return blockData.propagatesSkylightDown(((CraftWorld) world).getHandle(), new net.minecraft.core.BlockPos(position.getX(), position.getY(), position.getZ()));
    }

    @Override
    public boolean regenerate(org.bukkit.World bukkitWorld, Region region, Extent extent, RegenOptions options) {
        try {
            doRegen(bukkitWorld, region, extent, options);
        } catch (Exception e) {
            throw new IllegalStateException("Regen failed.", e);
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    private Dynamic<net.minecraft.nbt.Tag> recursivelySetSeed(Dynamic<net.minecraft.nbt.Tag> dynamic, long seed, Set<Dynamic<net.minecraft.nbt.Tag>> seen) {
        if (!seen.add(dynamic)) {
            return dynamic;
        }
        return dynamic.updateMapValues(pair -> {
            if (pair.getFirst().asString("").equals("seed")) {
                return pair.mapSecond(v -> v.createLong(seed));
            }
            if (pair.getSecond().getValue() instanceof CompoundTag) {
                return pair.mapSecond(v -> recursivelySetSeed((Dynamic<net.minecraft.nbt.Tag>) v, seed, seen));
            }
            return pair;
        });
    }

    private void doRegen(org.bukkit.World bukkitWorld, Region region, Extent extent, RegenOptions options) throws Exception {
        Environment env = bukkitWorld.getEnvironment();
        ChunkGenerator gen = bukkitWorld.getGenerator();

        Path tempDir = Files.createTempDirectory("WorldEditWorldGen");
        LevelStorageSource convertable = LevelStorageSource.createDefault(tempDir);
        ResourceKey<LevelStem> worldDimKey = getWorldDimKey(env);
        try (net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess session = convertable.c("worldeditregentempworld", worldDimKey)) {
            ServerLevel originalWorld = ((CraftWorld) bukkitWorld).getHandle();
            PrimaryLevelData originalWorldData = originalWorld.serverLevelData;

            long seed = options.getSeed().orElse(originalWorld.getSeed());

            WorldData levelProperties = originalWorld.getServer().getWorldData();
            RegistryReadOps<Tag> nbtRegOps = RegistryReadOps.a(
                NbtOps.INSTANCE,
                originalWorld.getServer().resources.getResourceManager(),
                RegistryAccess.b()
            );

            net.minecraft.world.level.levelgen.WorldGenSettings newOpts = WorldGenSettings.CODEC
                .encodeStart(nbtRegOps, levelProperties.worldGenSettings())
                .flatMap(tag ->
                    WorldGenSettings.CODEC.parse(
                        recursivelySetSeed(new Dynamic<>(nbtRegOps, tag), seed, new HashSet<>())
                    )
                )
                .result()
                .orElseThrow(() -> new IllegalStateException("Unable to map GeneratorOptions"));


            net.minecraft.world.level.LevelSettings newWorldSettings = new net.minecraft.world.level.LevelSettings("worldeditregentempworld",
                originalWorldData.settings.gameType(),
                originalWorldData.settings.hardcore,
                originalWorldData.settings.difficulty(),
                originalWorldData.settings.allowCommands(),
                originalWorldData.settings.gameRules(),
                originalWorldData.settings.getDataPackConfig());
            net.minecraft.world.level.storage.PrimaryLevelData newWorldData = new net.minecraft.world.level.storage.PrimaryLevelData(newWorldSettings, newOpts, Lifecycle.stable());

            net.minecraft.server.level.ServerLevel freshWorld = new net.minecraft.server.level.ServerLevel(
                originalWorld.getServer(),
                originalWorld.getServer().executor,
                session, newWorldData,
                originalWorld.getLevel().dimension(),
                originalWorld.getMinecraftWorld().dimensionType(),
                //originalWorld.getTypeKey(),
                new NoOpWorldLoadListener(),
                newOpts.dimensions().get(worldDimKey).generator(),
                originalWorld.isDebug(),
                seed,
                ImmutableList.of(),
                false,
                env, gen
            );
            try {
                regenForWorld(region, extent, freshWorld, options);
            } finally {
                freshWorld.getChunkSourceOH().close(false);
            }
        } finally {
            try {
                Map<String, org.bukkit.World> map = (Map<String, org.bukkit.World>) serverWorldsField.get(Bukkit.getServer());
                map.remove("worldeditregentempworld");
            } catch (IllegalAccessException ignored) {
            }
            SafeFiles.tryHardToDeleteDir(tempDir);
        }
    }

    private BiomeType adapt(ServerLevel serverWorld, Biome origBiome) {
        ResourceLocation key = serverWorld.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY).getKey(origBiome);
        if (key == null) {
            return null;
        }
        return BiomeTypes.get(key.toString());
    }

    private void regenForWorld(Region region, Extent extent, ServerLevel serverWorld, RegenOptions options) throws WorldEditException {
        List<CompletableFuture<ChunkAccess>> chunkLoadings = submitChunkLoadTasks(region, serverWorld);
        net.minecraft.util.thread.BlockableEventLoop<?> executor;
        try {
            executor = (net.minecraft.util.thread.BlockableEventLoop<?>) chunkProviderExecutorField.get(serverWorld.asyncChunkTaskManager);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Couldn't get executor for chunk loading.", e);
        }
        executor.managedBlock(() -> {
            // bail out early if a future fails
            if (chunkLoadings.stream().anyMatch(ftr ->
                ftr.isDone() && Futures.getUnchecked(ftr) == null
            )) {
                return false;
            }
            return chunkLoadings.stream().allMatch(CompletableFuture::isDone);
        });
        Map<net.minecraft.world.level.ChunkPos, net.minecraft.world.level.chunk.ChunkAccess> chunks = new HashMap<>();
        for (CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess> future : chunkLoadings) {
            @Nullable
            net.minecraft.world.level.chunk.ChunkAccess chunk = future.getNow(null);
            checkState(chunk != null, "Failed to generate a chunk, regen failed.");
            chunks.put(chunk.getPos(), chunk);
        }

        for (BlockVector3 vec : region) {
            BlockPos pos = new BlockPos(vec.getBlockX(), vec.getBlockY(), vec.getBlockZ());
            net.minecraft.world.level.chunk.ChunkAccess chunk = chunks.get(new net.minecraft.world.level.ChunkPos(pos));
            final net.minecraft.world.level.block.state.BlockState blockData = chunk.getBlockState(pos);
            int internalId = Block.getId(blockData);
            BlockStateHolder<?> state = BlockStateIdAccess.getBlockStateById(internalId);
            BlockEntity blockEntity = chunk.getBlockEntity(pos);
            if (blockEntity != null) {
                net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                blockEntity.save(tag);
                state = state.toBaseBlock(((CompoundBinaryTag) toNative(tag)));
            }
            extent.setBlock(vec, state.toBaseBlock());
            if (options.shouldRegenBiomes()) {
                ChunkBiomeContainer biomeIndex = chunk.getBiomes();
                if (biomeIndex != null) {
                    Biome origBiome = biomeIndex.getNoiseBiome(vec.getBlockX(), vec.getBlockY(), vec.getBlockZ());
                    BiomeType adaptedBiome = adapt(serverWorld, origBiome);
                    if (adaptedBiome != null) {
                        extent.setBiome(vec, adaptedBiome);
                    }
                }
            }
        }
    }

    private List<CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess>> submitChunkLoadTasks(Region region, ServerLevel serverWorld) {
        net.minecraft.server.level.ServerChunkCache chunkManager = serverWorld.getChunkSourceOH();
        List<CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess>> chunkLoadings = new ArrayList<>();
        // Pre-gen all the chunks
        for (BlockVector2 chunk : region.getChunks()) {
            try {
                //noinspection unchecked
                chunkLoadings.add(
                    ((CompletableFuture<Either<ChunkAccess, Failure>>)
                        getChunkFutureMethod.invoke(chunkManager, chunk.getX(), chunk.getZ(), ChunkStatus.FEATURES, true))
                        .thenApply(either -> either.left().orElse(null))
                );
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Couldn't load chunk for regen.", e);
            }
        }
        return chunkLoadings;
    }

    private ResourceKey<LevelStem> getWorldDimKey(Environment env) {
        return switch (env) {
            case NETHER -> LevelStem.NETHER;
            case THE_END -> LevelStem.END;
            default -> LevelStem.OVERWORLD;
        };
    }

    private static final Set<SideEffect> SUPPORTED_SIDE_EFFECTS = Sets.immutableEnumSet(
            SideEffect.NEIGHBORS,
            SideEffect.LIGHTING,
            SideEffect.VALIDATION,
            SideEffect.ENTITY_AI,
            SideEffect.EVENTS
    );

    @Override
    public Set<SideEffect> getSupportedSideEffects() {
        return SUPPORTED_SIDE_EFFECTS;
    }

    // ------------------------------------------------------------------------
    // Code that is less likely to break
    // ------------------------------------------------------------------------

    /**
     * Converts from a non-native NMS NBT structure to a native WorldEdit NBT
     * structure.
     *
     * @param foreign non-native NMS NBT structure
     * @return native WorldEdit NBT structure
     */
    BinaryTag toNative(net.minecraft.nbt.Tag foreign) {
        if (foreign == null) {
            return null;
        }
        if (foreign instanceof net.minecraft.nbt.CompoundTag) {
            Map<String, BinaryTag> values = new HashMap<>();
            Set<String> foreignKeys = ((net.minecraft.nbt.CompoundTag) foreign).getAllKeys(); // map.keySet

            for (String str : foreignKeys) {
                net.minecraft.nbt.Tag base = ((net.minecraft.nbt.CompoundTag) foreign).get(str);
                values.put(str, toNative(base));
            }
            return CompoundBinaryTag.from(values);
        } else if (foreign instanceof net.minecraft.nbt.ByteTag) {
            return ByteBinaryTag.of(((ByteTag) foreign).getAsByte());
        } else if (foreign instanceof net.minecraft.nbt.ByteArrayTag) {
            return ByteArrayBinaryTag.of(((ByteArrayTag) foreign).getAsByteArray());
        } else if (foreign instanceof net.minecraft.nbt.DoubleTag) {
            return DoubleBinaryTag.of(((DoubleTag) foreign).getAsDouble());
        } else if (foreign instanceof net.minecraft.nbt.FloatTag) {
            return FloatBinaryTag.of(((FloatTag) foreign).getAsFloat());
        } else if (foreign instanceof net.minecraft.nbt.IntTag) {
            return IntBinaryTag.of(((IntTag) foreign).getAsInt());
        } else if (foreign instanceof net.minecraft.nbt.IntArrayTag) {
            return IntArrayBinaryTag.of(((IntArrayTag) foreign).getAsIntArray());
        } else if (foreign instanceof net.minecraft.nbt.LongArrayTag) {
            return LongArrayBinaryTag.of(((LongArrayTag) foreign).getAsLongArray());
        } else if (foreign instanceof net.minecraft.nbt.ListTag) {
            try {
                return toNativeList((net.minecraft.nbt.ListTag) foreign);
            } catch (Throwable e) {
                logger.log(Level.WARNING, "Failed to convert NBTTagList", e);
                return ListBinaryTag.empty();
            }
        } else if (foreign instanceof net.minecraft.nbt.LongTag) {
            return LongBinaryTag.of(((LongTag) foreign).getAsLong());
        } else if (foreign instanceof net.minecraft.nbt.ShortTag) {
            return ShortBinaryTag.of(((ShortTag) foreign).getAsShort());
        } else if (foreign instanceof StringTag) {
            return StringBinaryTag.of(foreign.getAsString());
        } else if (foreign instanceof EndTag) {
            return EndBinaryTag.get();
        } else {
            throw new IllegalArgumentException("Don't know how to make native " + foreign.getClass().getCanonicalName());
        }
    }

    /**
     * Convert a foreign NBT list tag into a native WorldEdit one.
     *
     * @param foreign the foreign tag
     * @return the converted tag
     * @throws SecurityException on error
     * @throws IllegalArgumentException on error
     * @throws IllegalAccessException on error
     */
    private ListBinaryTag toNativeList(ListTag foreign) throws SecurityException, IllegalArgumentException, IllegalAccessException {
        ListBinaryTag.Builder<BinaryTag> values = ListBinaryTag.builder();

        List<?> foreignList;
        foreignList = (List<?>) nbtListTagListField.get(foreign);
        for (int i = 0; i < foreign.size(); i++) {
            net.minecraft.nbt.Tag element = (Tag) foreignList.get(i);
            values.add(toNative(element)); // List elements shouldn't have names
        }

        return values.build();
    }

    /**
     * Converts a WorldEdit-native NBT structure to a NMS structure.
     *
     * @param foreign structure to convert
     * @return non-native structure
     */
    net.minecraft.nbt.Tag fromNative(BinaryTag foreign) {
        if (foreign == null) {
            return null;
        }
        if (foreign instanceof CompoundBinaryTag) {
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            for (String key : ((CompoundBinaryTag) foreign).keySet()) {
                tag.put(key, fromNative(((CompoundBinaryTag) foreign).get(key)));
            }
            return tag;
        } else if (foreign instanceof ByteBinaryTag) {
            return net.minecraft.nbt.ByteTag.valueOf((((ByteBinaryTag) foreign).value()));
        } else if (foreign instanceof ByteArrayBinaryTag) {
            return new net.minecraft.nbt.ByteArrayTag(((ByteArrayBinaryTag) foreign).value());
        } else if (foreign instanceof DoubleBinaryTag) {
            return net.minecraft.nbt.DoubleTag.valueOf(((DoubleBinaryTag) foreign).value());
        } else if (foreign instanceof FloatBinaryTag) {
            return net.minecraft.nbt.FloatTag.valueOf(((FloatBinaryTag) foreign).value());
        } else if (foreign instanceof IntBinaryTag) {
            return net.minecraft.nbt.IntTag.valueOf(((IntBinaryTag) foreign).value());
        } else if (foreign instanceof IntArrayBinaryTag) {
            return new net.minecraft.nbt.IntArrayTag(((IntArrayBinaryTag) foreign).value());
        } else if (foreign instanceof LongArrayBinaryTag) {
            return new net.minecraft.nbt.LongArrayTag(((LongArrayBinaryTag) foreign).value());
        } else if (foreign instanceof ListBinaryTag) {
            net.minecraft.nbt.ListTag tag = new net.minecraft.nbt.ListTag();
            ListBinaryTag foreignList = (ListBinaryTag) foreign;
            for (BinaryTag t : foreignList) {
                tag.add(fromNative(t));
            }
            return tag;
        } else if (foreign instanceof LongBinaryTag) {
            return net.minecraft.nbt.LongTag.valueOf(((LongBinaryTag) foreign).value());
        } else if (foreign instanceof ShortBinaryTag) {
            return net.minecraft.nbt.ShortTag.valueOf(((ShortBinaryTag) foreign).value());
        } else if (foreign instanceof StringBinaryTag) {
            return net.minecraft.nbt.StringTag.valueOf(((StringBinaryTag) foreign).value());
        } else if (foreign instanceof EndBinaryTag) {
            return net.minecraft.nbt.EndTag.INSTANCE;
        } else {
            throw new IllegalArgumentException("Don't know how to make NMS " + foreign.getClass().getCanonicalName());
        }
    }

    @Override
    public boolean supportsWatchdog() {
        return watchdog != null;
    }

    @Override
    public void tickWatchdog() {
        watchdog.tick();
    }

    private static class SpigotWatchdog implements Watchdog {
        @Override
        public void tick() {
            WatchdogThread.tick();
        }
    }

    private static class MojangWatchdog implements Watchdog {
        private final net.minecraft.server.MinecraftServer server;
        private final Field tickField;

        MojangWatchdog(net.minecraft.server.MinecraftServer server) throws NoSuchFieldException {
            this.server = server;
            Field tickField = net.minecraft.server.MinecraftServer.class.getDeclaredField("nextTickTime");
            tickField.setAccessible(true);
            this.tickField = tickField;
        }

        @Override
        public void tick() {
            try {
                tickField.set(server, Util.getEpochMillis());
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    private static class NoOpWorldLoadListener implements net.minecraft.server.level.progress.ChunkProgressListener {

        @Override
        public void updateSpawnPos(ChunkPos chunkPos) {

        }

        @Override
        public void onStatusChange(ChunkPos chunkPos, @Nullable ChunkStatus chunkStatus) {

        }

        @Override
        public void stop() {

        }

        @Override
        public void setChunkRadius(int i) {

        }
    }
}

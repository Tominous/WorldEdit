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

package com.sk89q.worldedit.extent.clipboard.io;

import com.google.common.collect.ImmutableList;
import com.sk89q.jnbt.ByteArrayTag;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.IntTag;
import com.sk89q.jnbt.ListTag;
import com.sk89q.jnbt.NBTInputStream;
import com.sk89q.jnbt.NamedTag;
import com.sk89q.jnbt.ShortTag;
import com.sk89q.jnbt.StringTag;
import com.sk89q.jnbt.Tag;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.legacycompat.EntityNBTCompatibilityHandler;
import com.sk89q.worldedit.extent.clipboard.io.legacycompat.FlowerPotCompatibilityHandler;
import com.sk89q.worldedit.extent.clipboard.io.legacycompat.NBTCompatibilityHandler;
import com.sk89q.worldedit.extent.clipboard.io.legacycompat.NoteBlockCompatibilityHandler;
import com.sk89q.worldedit.extent.clipboard.io.legacycompat.Pre13HangingCompatibilityHandler;
import com.sk89q.worldedit.extent.clipboard.io.legacycompat.SignCompatibilityHandler;
import com.sk89q.worldedit.extent.clipboard.io.legacycompat.SkullBlockCompatibilityHandler;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.entity.EntityType;
import com.sk89q.worldedit.world.entity.EntityTypes;
import com.sk89q.worldedit.world.registry.LegacyMapper;
import com.sk89q.worldedit.world.storage.NBTConversions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Reads schematic files that are compatible with MCEdit and other editors.
 */
public class MCEditSchematicReader extends NBTSchematicReader {

    private static final ImmutableList<NBTCompatibilityHandler> COMPATIBILITY_HANDLERS
        = ImmutableList.of(
        new SignCompatibilityHandler(),
        new FlowerPotCompatibilityHandler(),
        new NoteBlockCompatibilityHandler(),
        new SkullBlockCompatibilityHandler()
        // TODO - item tags for inventories...? DFUs :>
    );
    private static final ImmutableList<EntityNBTCompatibilityHandler> ENTITY_COMPATIBILITY_HANDLERS
        = ImmutableList.of(
        new Pre13HangingCompatibilityHandler()
    );

    private static final Logger log = LoggerFactory.getLogger(MCEditSchematicReader.class);
    private final NBTInputStream inputStream;

    /**
     * Create a new instance.
     *
     * @param inputStream the input stream to read from
     */
    public MCEditSchematicReader(NBTInputStream inputStream) {
        checkNotNull(inputStream);
        this.inputStream = inputStream;
    }

    @Override
    public Clipboard read() throws IOException {
        // Schematic tag
        NamedTag rootTag = inputStream.readNamedTag();
        if (!rootTag.getName().equals("Schematic")) {
            throw new IOException("Tag 'Schematic' does not exist or is not first");
        }
        CompoundTag schematicTag = (CompoundTag) rootTag.getTag();

        // Check
        Map<String, Tag> schematic = schematicTag.getValue();
        if (!schematic.containsKey("Blocks")) {
            throw new IOException("Schematic file is missing a 'Blocks' tag");
        }

        // Check type of Schematic
        String materials = requireTag(schematic, "Materials", StringTag.class).getValue();
        if (!materials.equals("Alpha")) {
            throw new IOException("Schematic file is not an Alpha schematic");
        }

        // ====================================================================
        // Metadata
        // ====================================================================

        BlockVector3 origin;
        Region region;

        // Get information
        short width = requireTag(schematic, "Width", ShortTag.class).getValue();
        short height = requireTag(schematic, "Height", ShortTag.class).getValue();
        short length = requireTag(schematic, "Length", ShortTag.class).getValue();

        try {
            int originX = requireTag(schematic, "WEOriginX", IntTag.class).getValue();
            int originY = requireTag(schematic, "WEOriginY", IntTag.class).getValue();
            int originZ = requireTag(schematic, "WEOriginZ", IntTag.class).getValue();
            BlockVector3 min = BlockVector3.at(originX, originY, originZ);

            int offsetX = requireTag(schematic, "WEOffsetX", IntTag.class).getValue();
            int offsetY = requireTag(schematic, "WEOffsetY", IntTag.class).getValue();
            int offsetZ = requireTag(schematic, "WEOffsetZ", IntTag.class).getValue();
            BlockVector3 offset = BlockVector3.at(offsetX, offsetY, offsetZ);

            origin = min.subtract(offset);
            region = new CuboidRegion(min, min.add(width, height, length).subtract(BlockVector3.ONE));
        } catch (IOException ignored) {
            origin = BlockVector3.ZERO;
            region = new CuboidRegion(origin, origin.add(width, height, length).subtract(BlockVector3.ONE));
        }

        // ====================================================================
        // Blocks
        // ====================================================================

        // Get blocks
        byte[] blockId = requireTag(schematic, "Blocks", ByteArrayTag.class).getValue();
        byte[] blockData = requireTag(schematic, "Data", ByteArrayTag.class).getValue();
        byte[] addId = new byte[0];
        short[] blocks = new short[blockId.length]; // Have to later combine IDs

        // We support 4096 block IDs using the same method as vanilla Minecraft, where
        // the highest 4 bits are stored in a separate byte array.
        if (schematic.containsKey("AddBlocks")) {
            addId = requireTag(schematic, "AddBlocks", ByteArrayTag.class).getValue();
        }

        // Combine the AddBlocks data with the first 8-bit block ID
        for (int index = 0; index < blockId.length; index++) {
            if ((index >> 1) >= addId.length) { // No corresponding AddBlocks index
                blocks[index] = (short) (blockId[index] & 0xFF);
            } else {
                if ((index & 1) == 0) {
                    blocks[index] = (short) (((addId[index >> 1] & 0x0F) << 8) + (blockId[index] & 0xFF));
                } else {
                    blocks[index] = (short) (((addId[index >> 1] & 0xF0) << 4) + (blockId[index] & 0xFF));
                }
            }
        }

        // Need to pull out tile entities
        List<Tag> tileEntities = requireTag(schematic, "TileEntities", ListTag.class).getValue();
        Map<BlockVector3, Map<String, Tag>> tileEntitiesMap = new HashMap<>();
        Map<BlockVector3, BlockState> blockOverrides = new HashMap<>();

        for (Tag tag : tileEntities) {
            if (!(tag instanceof CompoundTag)) continue;
            CompoundTag t = (CompoundTag) tag;

            int x = t.getInt("x");
            int y = t.getInt("y");
            int z = t.getInt("z");
            String id = t.getString("id");

            Map<String, Tag> values = new HashMap<>(t.getValue());
            values.put("id", new StringTag(convertBlockEntityId(id)));

            int index = y * width * length + z * width + x;
            BlockState block = LegacyMapper.getInstance().getBlockFromLegacy(blocks[index], blockData[index]);
            BlockState newBlock = block;
            if (newBlock != null) {
                for (NBTCompatibilityHandler handler : COMPATIBILITY_HANDLERS) {
                    if (handler.isAffectedBlock(newBlock)) {
                        newBlock = handler.updateNBT(block, values);
                        if (newBlock == null) {
                            break;
                        }
                    }
                }
            }

            BlockVector3 vec = BlockVector3.at(x, y, z);
            tileEntitiesMap.put(vec, values);
            if (newBlock != block) {
                blockOverrides.put(vec, newBlock);
            }
        }

        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(origin);


        for (int x = 0; x < width; ++x) {
            for (int y = 0; y < height; ++y) {
                for (int z = 0; z < length; ++z) {
                    int index = y * width * length + z * width + x;
                    BlockVector3 pt = BlockVector3.at(x, y, z);
                    boolean useOverride = blockOverrides.containsKey(pt);
                    BlockState state = useOverride
                            ? blockOverrides.get(pt)
                            : LegacyMapper.getInstance().getBlockFromLegacy(blocks[index], blockData[index]);

                    try {
                        if (state != null) {
                            if (tileEntitiesMap.containsKey(pt)) {
                                clipboard.setBlock(region.getMinimumPoint().add(pt), state.toBaseBlock(new CompoundTag(tileEntitiesMap.get(pt))));
                            } else {
                                clipboard.setBlock(region.getMinimumPoint().add(pt), state);
                            }
                        } else {
                            if (!useOverride) {
                                log.warn("Unknown block when pasting schematic: " + blocks[index] + ":" + blockData[index] + ". Please report this issue.");
                            }
                        }
                    } catch (WorldEditException ignored) { // BlockArrayClipboard won't throw this
                    }
                }
            }
        }

        // ====================================================================
        // Entities
        // ====================================================================

        ListTag entityList = getTag(schematic, "Entities", ListTag.class);
        if (entityList != null) {
            List<Tag> entityTags = entityList.getValue();
            for (Tag tag : entityTags) {
                if (tag instanceof CompoundTag) {
                    CompoundTag compound = (CompoundTag) tag;
                    String id = convertEntityId(compound.getString("id"));
                    Location location = NBTConversions.toLocation(clipboard, compound.getListTag("Pos"), compound.getListTag("Rotation"));

                    if (!id.isEmpty()) {
                        EntityType entityType = EntityTypes.get(id.toLowerCase());
                        if (entityType != null) {
                            for (EntityNBTCompatibilityHandler compatibilityHandler : ENTITY_COMPATIBILITY_HANDLERS) {
                                if (compatibilityHandler.isAffectedEntity(entityType, compound)) {
                                    compound = compatibilityHandler.updateNBT(entityType, compound);
                                }
                            }
                            BaseEntity state = new BaseEntity(entityType, compound);
                            clipboard.createEntity(location, state);
                        } else {
                            log.warn("Unknown entity when pasting schematic: " + id.toLowerCase());
                        }
                    }
                }
            }
        }

        return clipboard;
    }

    private String convertEntityId(String id) {
        switch(id) {
            case "AreaEffectCloud": return "area_effect_cloud";
            case "ArmorStand": return "armor_stand";
            case "CaveSpider": return "cave_spider";
            case "MinecartChest": return "chest_minecart";
            case "MinecartCommandBlock": return "commandblock_minecart";
            case "DragonFireball": return "dragon_fireball";
            case "ThrownEgg": return "egg";
            case "EnderCrystal": return "ender_crystal";
            case "EnderDragon": return "ender_dragon";
            case "ThrownEnderpearl": return "ender_pearl";
            case "EyeOfEnderSignal": return "eye_of_ender_signal";
            case "FallingSand": return "falling_block";
            case "FireworksRocketEntity": return "fireworks_rocket";
            case "MinecartFurnace": return "furnace_minecart";
            case "MinecartHopper": return "hopper_minecart";
            case "EntityHorse": return "horse";
            case "ItemFrame": return "item_frame";
            case "LeashKnot": return "leash_knot";
            case "LightningBolt": return "lightning_bolt";
            case "LavaSlime": return "magma_cube";
            case "MinecartRideable": return "minecart";
            case "MushroomCow": return "mooshroom";
            case "Ozelot": return "ocelot";
            case "PolarBear": return "polar_bear";
            case "ThrownPotion": return "potion";
            case "ShulkerBullet": return "shulker_bullet";
            case "SmallFireball": return "small_fireball";
            case "MinecartSpawner": return "spawner_minecart";
            case "SpectralArrow": return "spectral_arrow";
            case "PrimedTnt": return "tnt";
            case "MinecartTNT": return "tnt_minecart";
            case "VillagerGolem": return "villager_golem";
            case "WitherBoss": return "wither";
            case "WitherSkull": return "wither_skull";
            case "ThrownExpBottle": return "xp_bottle";
            case "XPOrb": return "xp_orb";
            case "PigZombie": return "zombie_pigman";
            default: return id;
        }
    }

    private String convertBlockEntityId(String id) {
        switch(id) {
            case "Cauldron": return "brewing_stand";
            case "Control": return "command_block";
            case "DLDetector": return "daylight_detector";
            case "Trap": return "dispenser";
            case "EnchantTable": return "enchanting_table";
            case "EndGateway": return "end_gateway";
            case "AirPortal": return "end_portal";
            case "EnderChest": return "ender_chest";
            case "FlowerPot": return "flower_pot";
            case "RecordPlayer": return "jukebox";
            case "MobSpawner": return "mob_spawner";
            case "Music":
            case "noteblock":
                return "note_block";
            case "Structure": return "structure_block";
            default: return id;
        }
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
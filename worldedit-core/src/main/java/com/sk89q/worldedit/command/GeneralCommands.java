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

package com.sk89q.worldedit.command;

import static com.google.common.base.Preconditions.checkNotNull;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalConfiguration;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.command.util.CommandPermissions;
import com.sk89q.worldedit.command.util.CommandPermissionsConditionGenerator;
import com.sk89q.worldedit.command.util.WorldEditAsyncCommandBuilder;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extension.input.DisallowedUsageException;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.util.formatting.component.PaginationBox;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent;
import com.sk89q.worldedit.util.formatting.text.format.TextColor;
import com.sk89q.worldedit.world.item.ItemType;
import org.enginehub.piston.annotation.Command;
import org.enginehub.piston.annotation.CommandContainer;
import org.enginehub.piston.annotation.param.Arg;
import org.enginehub.piston.annotation.param.ArgFlag;
import org.enginehub.piston.annotation.param.Switch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * General WorldEdit commands.
 */
@CommandContainer(superTypes = CommandPermissionsConditionGenerator.Registration.class)
public class GeneralCommands {

    private final WorldEdit worldEdit;

    /**
     * Create a new instance.
     *
     * @param worldEdit reference to WorldEdit
     */
    public GeneralCommands(WorldEdit worldEdit) {
        checkNotNull(worldEdit);
        this.worldEdit = worldEdit;
    }

    @Command(
        name = "/limit",
        desc = "Modify block change limit"
    )
    @CommandPermissions("worldedit.limit")
    public void limit(Player player, LocalSession session,
                      @Arg(desc = "The limit to set", def = "")
                          Integer limit) {

        LocalConfiguration config = worldEdit.getConfiguration();
        boolean mayDisable = player.hasPermission("worldedit.limit.unrestricted");

        limit = limit == null ? config.defaultChangeLimit : Math.max(-1, limit);
        if (!mayDisable && config.maxChangeLimit > -1) {
            if (limit > config.maxChangeLimit) {
                player.printError(TranslatableComponent.of("worldedit.limit.too-high", TextComponent.of(config.maxChangeLimit)));
                return;
            }
        }

        session.setBlockChangeLimit(limit);
        Component component = TextComponent.empty().append(TranslatableComponent.of("worldedit.limit.set", TextComponent.of(limit)));
        if (limit != config.calculationTimeout) {
            component.append(TranslatableComponent.of("worldedit.limit.return-to-default", TextColor.GRAY));
        }
        player.printInfo(component);
    }

    @Command(
        name = "/timeout",
        desc = "Modify evaluation timeout time."
    )
    @CommandPermissions("worldedit.timeout")
    public void timeout(Player player, LocalSession session,
                        @Arg(desc = "The timeout time to set", def = "")
                            Integer limit) {
        LocalConfiguration config = worldEdit.getConfiguration();
        boolean mayDisable = player.hasPermission("worldedit.timeout.unrestricted");

        limit = limit == null ? config.calculationTimeout : Math.max(-1, limit);
        if (!mayDisable && config.maxCalculationTimeout > -1) {
            if (limit > config.maxCalculationTimeout) {
                player.printError(TranslatableComponent.of("worldedit.timeout.too-high", TextComponent.of(config.maxCalculationTimeout)));
                return;
            }
        }

        session.setTimeout(limit);
        Component component = TextComponent.empty().append(TranslatableComponent.of("worldedit.timeout.set", TextComponent.of(limit)));
        if (limit != config.calculationTimeout) {
            component.append(TranslatableComponent.of("worldedit.timeout.return-to-default", TextColor.GRAY));
        }
        player.printInfo(component);
    }

    @Command(
        name = "/fast",
        desc = "Toggle fast mode"
    )
    @CommandPermissions("worldedit.fast")
    public void fast(Player player, LocalSession session,
                     @Arg(desc = "The new fast mode state", def = "")
                        Boolean fastMode) {
        boolean hasFastMode = session.hasFastMode();
        if (fastMode != null && fastMode == hasFastMode) {
            player.printError(TranslatableComponent.of(fastMode ? "worldedit.fast.enabled.already" : "worldedit.fast.disabled.already"));
            return;
        }

        if (hasFastMode) {
            session.setFastMode(false);
            player.printInfo(TranslatableComponent.of("worldedit.fast.disabled"));
        } else {
            session.setFastMode(true);
            player.printInfo(TranslatableComponent.of("worldedit.fast.enabled"));
        }
    }

    @Command(
        name = "/reorder",
        desc = "Sets the reorder mode of WorldEdit"
    )
    @CommandPermissions("worldedit.reorder")
    public void reorderMode(Player player, LocalSession session,
                            @Arg(desc = "The reorder mode", def = "")
                                EditSession.ReorderMode reorderMode) {
        if (reorderMode == null) {
            player.printInfo(TranslatableComponent.of("worldedit.reorder.current", TextComponent.of(session.getReorderMode().getDisplayName())));
        } else {
            session.setReorderMode(reorderMode);
            player.printInfo(TranslatableComponent.of("worldedit.reorder.set", TextComponent.of(session.getReorderMode().getDisplayName())));
        }
    }

    @Command(
        name = "/drawsel",
        desc = "Toggle drawing the current selection"
    )
    @CommandPermissions("worldedit.drawsel")
    public void drawSelection(Player player, LocalSession session,
                              @Arg(desc = "The new draw selection state", def = "")
                                  Boolean drawSelection) throws WorldEditException {
        if (!WorldEdit.getInstance().getConfiguration().serverSideCUI) {
            throw new DisallowedUsageException("This functionality is disabled in the configuration!");
        }
        boolean useServerCui = session.shouldUseServerCUI();
        if (drawSelection != null && drawSelection == useServerCui) {
            player.printError(TranslatableComponent.of(useServerCui ? "worldedit.drawsel.enabled.already" : "worldedit.drawsel.disabled.already"));
            return;
        }
        if (useServerCui) {
            session.setUseServerCUI(false);
            session.updateServerCUI(player);
            player.printInfo(TranslatableComponent.of("worldedit.drawsel.disabled"));
        } else {
            session.setUseServerCUI(true);
            session.updateServerCUI(player);
            player.printInfo(TranslatableComponent.of("worldedit.drawsel.enabled"));
        }
    }

    @Command(
        name = "gmask",
        aliases = {"/gmask"},
        desc = "Set the global mask"
    )
    @CommandPermissions("worldedit.global-mask")
    public void gmask(Player player, LocalSession session,
                      @Arg(desc = "The mask to set", def = "")
                          Mask mask) {
        if (mask == null) {
            session.setMask(null);
            player.printInfo(TranslatableComponent.of("worldedit.gmask.disabled"));
        } else {
            session.setMask(mask);
            player.printInfo(TranslatableComponent.of("worldedit.gmask.set"));
        }
    }

    @Command(
        name = "toggleplace",
        aliases = {"/toggleplace"},
        desc = "Switch between your position and pos1 for placement"
    )
    public void togglePlace(Player player, LocalSession session) {
        if (session.togglePlacementPosition()) {
            player.printInfo(TranslatableComponent.of("worldedit.toggleplace.pos1"));
        } else {
            player.printInfo(TranslatableComponent.of("worldedit.toggleplace.player"));
        }
    }

    @Command(
        name = "searchitem",
        aliases = {"/searchitem", "/l", "/search"},
        desc = "Search for an item"
    )
    @CommandPermissions("worldedit.searchitem")
    public void searchItem(Actor actor,
                           @Switch(name = 'b', desc = "Only search for blocks")
                               boolean blocksOnly,
                           @Switch(name = 'i', desc = "Only search for items")
                               boolean itemsOnly,
                           @ArgFlag(name = 'p', desc = "Page of results to return", def = "1")
                               int page,
                           @Arg(desc = "Search query", variable = true)
                               List<String> query) {
        String search = String.join(" ", query);
        if (search.length() <= 2) {
            actor.printError(TranslatableComponent.of("worldedit.searchitem.too-short"));
            return;
        }
        if (blocksOnly && itemsOnly) {
            actor.printError(TranslatableComponent.of("worldedit.searchitem.b-and-i"));
            return;
        }

        WorldEditAsyncCommandBuilder.createAndSendMessage(actor, new ItemSearcher(search, blocksOnly, itemsOnly, page),
                "(Please wait... searching items.)");
    }

    private static class ItemSearcher implements Callable<Component> {
        private final boolean blocksOnly;
        private final boolean itemsOnly;
        private final String search;
        private final int page;

        ItemSearcher(String search, boolean blocksOnly, boolean itemsOnly, int page) {
            this.blocksOnly = blocksOnly;
            this.itemsOnly = itemsOnly;
            this.search = search;
            this.page = page;
        }

        @Override
        public Component call() throws Exception {
            String command = "/searchitem " + (blocksOnly ? "-b " : "") + (itemsOnly ? "-i " : "") + "-p %page% " + search;
            Map<String, String> results = new TreeMap<>();
            String idMatch = search.replace(' ', '_');
            String nameMatch = search.toLowerCase(Locale.ROOT);
            for (ItemType searchType : ItemType.REGISTRY) {
                if (blocksOnly && !searchType.hasBlockType()) {
                    continue;
                }

                if (itemsOnly && searchType.hasBlockType()) {
                    continue;
                }
                final String id = searchType.getId();
                String name = searchType.getName();
                final boolean hasName = !name.equals(id);
                name = name.toLowerCase(Locale.ROOT);
                if (id.contains(idMatch) || (hasName && name.contains(nameMatch))) {
                    results.put(id, name + (hasName ? " (" + id + ")" : ""));
                }
            }
            List<String> list = new ArrayList<>(results.values());
            return PaginationBox.fromStrings("Search results for '" + search + "'", command, list).create(page);
        }
    }
}

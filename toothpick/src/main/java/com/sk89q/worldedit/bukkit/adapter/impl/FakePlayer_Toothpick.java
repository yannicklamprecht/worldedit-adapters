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

import java.util.OptionalInt;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.inventory.AbstractContainerMenu;

class FakePlayer_Toothpick extends net.minecraft.server.level.ServerPlayer {
    private static final GameProfile FAKE_WORLDEDIT_PROFILE = new GameProfile(UUID.nameUUIDFromBytes("worldedit".getBytes()), "[WorldEdit]");

    FakePlayer_Toothpick(ServerLevel world) {
        super(world.getServer(), world, FAKE_WORLDEDIT_PROFILE, new net.minecraft.server.level.ServerPlayerGameMode(world));
    }

    @Override
    public net.minecraft.world.phys.Vec3 position() {
        return new net.minecraft.world.phys.Vec3(0, 0, 0);
    }

    @Override
    public void tick() {
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isCreative() {
        return false;
    }

    @Override
    public void die(DamageSource damagesource) {
    }


    @Override
    public OptionalInt openMenu(@Nullable net.minecraft.world.MenuProvider itileinventory) {
        return OptionalInt.empty();
    }

    @Override
    public void updateOptions(net.minecraft.network.protocol.game.ServerboundClientInformationPacket packetplayinsettings) {
    }

    @Override
    public void displayClientMessage(Component ichatbasecomponent, boolean flag) {
    }

    @Override
    public void awardStat(net.minecraft.stats.Stat<?> statistic, int i) {
    }

    @Override
    public void resetStat(net.minecraft.stats.Stat<?> statistic) {
    }

    @Override
    public boolean isInvulnerableTo(DamageSource damagesource) {
        return true;
    }

    @Override
    public boolean drop(boolean flag) { // canEat, search for foodData usage
        return true;
    }

    @Override
    public void refreshContainer(AbstractContainerMenu container) {
    }

    @Override
    public void openTextEdit(net.minecraft.world.level.block.entity.SignBlockEntity tileentitysign) {
    }
}

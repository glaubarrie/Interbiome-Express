package dev.knoppid.ibe;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Menu for players WITHOUT the client mod: a plain vanilla 3-row chest screen.
 * Column 0 = furnaces, columns 4-8 = fuel, the grey glass panes in between are only decoration
 * (the middle one shows speed and fuel).
 */
public class GlassMenu extends ChestMenu {
	private static final int[] FURNACE_SLOTS = {0, 9, 18};
	private static final int INFO = 13, CONTAINER_END = 27, INVENTORY_END = 63;

	private final ExpressCart cart;
	private final SimpleContainer panes;
	private final boolean mod;   // viewer has the client mod: empty pane slots, lit furnace look, state packets instead of the info pane
	private final ServerPlayer viewer;
	private String info = "";
	private int lastSpeed = -1, lastBurn = -1;

	public static GlassMenu create(int id, Inventory inv, ExpressCart cart, boolean mod) {
		SimpleContainer panes = new SimpleContainer(27);
		if (!mod) {
			ItemStack pane = pane();
			for (int i = 0; i < 27; i++) panes.setItem(i, pane.copy());
		}
		return new GlassMenu(id, inv, cart, panes, mod);
	}

	private GlassMenu(int id, Inventory inv, ExpressCart cart, SimpleContainer panes, boolean mod) {
		super(MenuType.GENERIC_9x3, id, inv, panes, 3);
		this.cart = cart;
		this.panes = panes;
		this.mod = mod;
		this.viewer = inv.player instanceof ServerPlayer sp ? sp : null;
		for (int i = 0; i < 27; i++) {
			int col = i % 9, row = i / 9;
			Slot old = slots.get(i);
			Slot s;
			if (col == 0) {
				s = new Slot(cart.inv, row, old.x, old.y) {
					@Override public boolean mayPlace(ItemStack st) { return st.is(Items.FURNACE) || st.is(Items.BLAST_FURNACE); }
					@Override public int getMaxStackSize() { return 1; }
					@Override public ItemStack getItem() {   // lit look for mod clients while the cart burns (display only)
						ItemStack raw = super.getItem();
						if (!mod || raw.isEmpty() || cart.menuData().get(1) <= 0) return raw;
						ItemStack c = raw.copy();
						c.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(ExpressMod.ID, raw.is(Items.BLAST_FURNACE) ? "lit_blast_furnace" : "lit_furnace"));
						return c;
					}
				};
			} else if (col >= 4) {
				s = new Slot(cart.inv, ExpressCart.FURNACES + row * 5 + (col - 4), old.x, old.y) {
					@Override public boolean mayPlace(ItemStack st) { return st.has(DataComponents.COOKING_FUEL); }
				};
			} else {
				s = new Slot(panes, i, old.x, old.y) {
					@Override public boolean mayPlace(ItemStack st) { return false; }
					@Override public boolean mayPickup(Player p) { return false; }
				};
			}
			s.index = i;
			slots.set(i, s);
		}
	}

	private static ItemStack pane() {
		Item item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("gray_stained_glass_pane"));
		ItemStack s = new ItemStack(item == Items.AIR ? Items.GLASS_PANE : item);
		s.set(DataComponents.ITEM_NAME, Component.literal(" "));
		return s;
	}

	@Override
	public void broadcastChanges() {
		var d = cart.menuData();
		if (mod) {
			if (viewer != null && (d.get(0) != lastSpeed || d.get(1) != lastBurn)) {
				lastSpeed = d.get(0);
				lastBurn = d.get(1);
				ServerPlayNetworking.send(viewer, new StatePayload(lastSpeed, lastBurn));
			}
			super.broadcastChanges();
			return;
		}
		String text = String.format("Speed: %.0f b/s  |  Fuel: %d%%", d.get(0) / 100.0, Math.round(d.get(1) / 10.0));
		if (!text.equals(info)) {
			info = text;
			ItemStack s = pane();
			s.set(DataComponents.ITEM_NAME, Component.literal(text));
			panes.setItem(INFO, s);
		}
		super.broadcastChanges();
	}

	@Override
	public boolean stillValid(Player p) {
		return cart.m != null && cart.m.isAlive() && p.distanceToSqr(cart.m) < 64.0;
	}

	@Override
	public ItemStack quickMoveStack(Player p, int idx) {
		Slot slot = slots.get(idx);
		if (!slot.hasItem() || (idx < CONTAINER_END && slot.container == panes)) return ItemStack.EMPTY;
		ItemStack st = idx < CONTAINER_END ? cart.inv.getItem(slot.getContainerSlot()) : slot.getItem();   // raw stack, not the lit-look copy
		ItemStack copy = st.copy();
		boolean moved = false;
		if (idx < CONTAINER_END) {
			moved = moveItemStackTo(st, CONTAINER_END, INVENTORY_END, true);
		} else if (st.is(Items.FURNACE) || st.is(Items.BLAST_FURNACE)) {
			for (int f : FURNACE_SLOTS) {
				if (st.isEmpty()) break;
				moved |= moveItemStackTo(st, f, f + 1, false);
			}
		} else if (st.has(DataComponents.COOKING_FUEL)) {
			for (int i = 0; i < 27 && !st.isEmpty(); i++) {
				if (i % 9 >= 4) moved |= moveItemStackTo(st, i, i + 1, false);
			}
		}
		if (!moved) return ItemStack.EMPTY;
		if (st.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
		return copy;
	}
}

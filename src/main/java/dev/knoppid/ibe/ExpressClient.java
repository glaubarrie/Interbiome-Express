package dev.knoppid.ibe;

import java.util.HashSet;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.ChestMenu;

public class ExpressClient implements ClientModInitializer {
	private static final Set<Integer> CARTS = new HashSet<>();   // entity ids of Express-Minecarts
	public static volatile int speed, burn;                     // from the server while a cart screen is open

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(OpenPayload.TYPE, (payload, ctx) -> { });   // marks this client as having the mod
		ClientPlayNetworking.registerGlobalReceiver(CartPayload.TYPE, (payload, ctx) -> CARTS.add(payload.id()));
		ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE, (payload, ctx) -> { speed = payload.speed(); burn = payload.burn(); });

		// The server opens a plain chest menu; we swap the chest screen for our own screen on the same menu.
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (screen instanceof ContainerScreen cs && cs.getMenu() instanceof ChestMenu menu
					&& "Express-Minecart".equals(cs.getTitle().getString()) && mc.player != null) {
				speed = 0;
				burn = 0;
				mc.gui.setScreen(new ExpressScreen(menu, mc.player.getInventory(), cs.getTitle()));
			}
		});

		// E while riding an Express-Minecart: take the key press before the inventory opens and ask the server for the cart screen.
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (mc.player == null || mc.player.getVehicle() == null || !CARTS.contains(mc.player.getVehicle().getId())) return;
			boolean pressed = false;
			while (mc.options.keyInventory.consumeClick()) pressed = true;
			if (pressed) ClientPlayNetworking.send(new OpenPayload());
		});
	}
}

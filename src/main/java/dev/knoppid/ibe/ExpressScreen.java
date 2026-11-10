package dev.knoppid.ibe;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;

/** Donkey-style layout: furnace slots on the left, cart picture + speed/burn in the middle, fuel grid on the right. */
public class ExpressScreen extends AbstractContainerScreen<ChestMenu> {
	private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");

	private static final Identifier FLAME = Identifier.fromNamespaceAndPath("interbiome_express", "container/flame");

	private static final Identifier FURNACE_ICON = Identifier.fromNamespaceAndPath("interbiome_express", "container/slot/furnace");

	public ExpressScreen(ChestMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 168);
		this.inventoryLabelY = this.imageHeight - 94;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractBackground(g, mouseX, mouseY, delta);
		int x = this.leftPos, y = this.topPos;

		// vanilla 3-row chest background (top part + player inventory part)
		g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0.0F, 0.0F, this.imageWidth, 71, 256, 256);
		g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y + 71, 0.0F, 126.0F, this.imageWidth, 96, 256, 256);

		// middle panel: 54x54 with the same bevel as three slots (dark top/left, white bottom/right)
		int px = x + 25, py = y + 17;
		g.fill(px, py, px + 54, py + 54, 0xFF8B8B8B);
		g.fill(px, py, px + 54, py + 1, 0xFF373737);
		g.fill(px, py, px + 1, py + 54, 0xFF373737);
		g.fill(px, py + 53, px + 54, py + 54, 0xFFFFFFFF);
		g.fill(px + 53, py, px + 54, py + 54, 0xFFFFFFFF);

		// outline icon in empty furnace slots
		for (int i = 0; i < 3; i++) {
			if (!this.menu.slots.get(i * 9).hasItem()) g.blitSprite(RenderPipelines.GUI_TEXTURED, FURNACE_ICON, x + 8, y + 18 + i * 18, 16, 16);
		}

		// big flame (own sprite, transparent background), cut off from the top as the fuel item burns down
		int h = (int) Math.ceil(28 * (ExpressClient.burn / 1000.0));
		if (h > 0) {
			g.enableScissor(x + 38, y + 48 - h, x + 66, y + 48);
			g.blitSprite(RenderPipelines.GUI_TEXTURED, FLAME, x + 38, y + 20, 28, 28);
			g.disableScissor();
		}
		g.centeredText(this.font, String.format("%.0f b/s", (ExpressClient.speed / 100.0)), x + 52, y + 55, 0xFFFFFFFF);
	}
}

package dev.knoppid.ibe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Express-Minecart is a plain vanilla minecart (marked by an attachment) and a plain vanilla minecart item
 * (marked by custom data), so it works on servers without any client mod. Players that do have the mod get the
 * fancy screen and the E key; everybody else gets a chest-style menu.
 */
public class ExpressMod implements ModInitializer {
	public static final String ID = "interbiome_express";
	public static final Logger LOG = LoggerFactory.getLogger("Interbiome-Express");
	private static final Component TITLE = Component.literal("Express-Minecart");

	public static final AttachmentType<ExpressCart> DATA = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath(ID, "cart"),
			b -> b.persistent(ExpressCart.CODEC).initializer(ExpressCart::new));

	private static final Set<Minecart> CARTS = new HashSet<>();
	private static final List<Object[]> CLEANUP = new ArrayList<>();   // {ticks left, level, x, y, z, ExpressCart}

	public static ItemStack makeItem() {
		ItemStack s = new ItemStack(Items.MINECART);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("ibe", true);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		s.set(DataComponents.ITEM_NAME, TITLE);
		return s;
	}

	private static boolean isExpressItem(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return s.is(Items.MINECART) && d != null && d.copyTag().getBooleanOr("ibe", false);
	}

	private static void open(ServerPlayer p, Minecart m) {
		ExpressCart c = m.getAttached(DATA);
		if (c == null) return;
		c.m = m;
		boolean mod = ServerPlayNetworking.canSend(p, OpenPayload.TYPE);
		p.openMenu(new SimpleMenuProvider((id, inv, pl) -> GlassMenu.create(id, inv, c, mod), TITLE));
	}

	@Override
	public void onInitialize() {
		IbeConfig.load();

		PayloadTypeRegistry.serverboundPlay().register(OpenPayload.TYPE, OpenPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(OpenPayload.TYPE, OpenPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CartPayload.TYPE, CartPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(OpenPayload.TYPE, (payload, ctx) -> {
			if (ctx.player().getVehicle() instanceof Minecart m) open(ctx.player(), m);
		});

		// Same creative tabs as the normal minecart.
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
				.register(out -> out.insertAfter(Items.MINECART, makeItem()));
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.REDSTONE_BLOCKS)
				.register(out -> out.insertAfter(Items.MINECART, makeItem()));

		// Placing the item on a rail creates a vanilla minecart carrying the stored contents.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			ItemStack st = player.getItemInHand(hand);
			BlockPos pos = hit.getBlockPos();
			if (player.isSpectator() || !isExpressItem(st) || !level.getBlockState(pos).is(BlockTags.RAILS)) return InteractionResult.PASS;
			if (level instanceof ServerLevel sl) {
				Minecart m = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("minecart")).create(sl, EntitySpawnReason.TRIGGERED) instanceof Minecart mc ? mc : null;
				if (m != null) {
					m.setPos(pos.getX() + 0.5, pos.getY() + 0.0625, pos.getZ() + 0.5);
					ExpressCart c = new ExpressCart();
					ItemContainerContents contents = st.get(DataComponents.CONTAINER);
					if (contents != null) c.restore(contents);
					m.setAttached(DATA, c);
					if (st.has(DataComponents.CUSTOM_NAME)) m.setCustomName(st.get(DataComponents.CUSTOM_NAME));
					sl.addFreshEntity(m);
					if (!player.isCreative()) st.shrink(1);
				}
			}
			return InteractionResult.SUCCESS;
		});

		// Sneak + right-click opens the inventory.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (entity instanceof Minecart m && m.hasAttached(DATA) && player.isSecondaryUseActive()) {
				if (player instanceof ServerPlayer sp) open(sp, m);
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});

		// Tell mod clients which minecarts are Express-Minecarts, so E can open the screen at once.
		EntityTrackingEvents.START_TRACKING.register((e, player) -> {
			if (e instanceof Minecart m && m.hasAttached(DATA) && ServerPlayNetworking.canSend(player, CartPayload.TYPE)) {
				ServerPlayNetworking.send(player, new CartPayload(m.getId()));
			}
		});

		ServerEntityEvents.ENTITY_LOAD.register((e, world) -> {
			if (e instanceof Minecart m && m.hasAttached(DATA)) CARTS.add(m);
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((e, world) -> {
			if (!(e instanceof Minecart m) || !CARTS.remove(m)) return;
			// Destroyed: replace vanilla's plain minecart drop by one item that keeps the contents (shulker-box style).
			if (m.getRemovalReason() == Entity.RemovalReason.KILLED && world instanceof ServerLevel sl) {
				CLEANUP.add(new Object[]{3, sl, m.getX(), m.getY(), m.getZ(), m.getAttached(DATA), m.getCustomName()});
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (Minecart m : new ArrayList<>(CARTS)) {
				ExpressCart c = m.getAttached(DATA);
				if (c != null && !m.isRemoved() && m.level() instanceof ServerLevel sl) c.tick(m, sl);
			}
			for (Object[] job : new ArrayList<>(CLEANUP)) {
				if ((int) job[0] > 0) { job[0] = (int) job[0] - 1; continue; }
				CLEANUP.remove(job);
				dropExpress((ServerLevel) job[1], (double) job[2], (double) job[3], (double) job[4], (ExpressCart) job[5], (Component) job[6]);
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registries, env) ->
				dispatcher.register(Commands.literal("ibe")
						.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
						.then(Commands.literal("reload").executes(ctx -> {
							IbeConfig.load();
							ctx.getSource().sendSuccess(() -> Component.literal("Interbiome-Express config reloaded"), true);
							return 1;
						}))));
	}

	private static void dropExpress(ServerLevel sl, double x, double y, double z, ExpressCart c, Component name) {
		for (ItemEntity ie : sl.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(x - 1.5, y - 1.5, z - 1.5, x + 1.5, y + 1.5, z + 1.5),
				e -> e.getItem().is(Items.MINECART) && !isExpressItem(e.getItem()))) {
			ie.discard();   // vanilla's plain minecart drop
			break;
		}
		ItemStack s = makeItem();
		if (c != null && c.hasContents()) s.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(c.inv.getItems()));
		if (name != null) s.set(DataComponents.CUSTOM_NAME, name);
		Containers.dropItemStack(sl, x, y, z, s);
	}
}

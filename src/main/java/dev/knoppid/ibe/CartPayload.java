package dev.knoppid.ibe;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client (mod clients only): "this minecart entity is an Express-Minecart". */
public record CartPayload(int id) implements CustomPacketPayload {
	public static final Type<CartPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ExpressMod.ID, "cart"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CartPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.VAR_INT, CartPayload::id, CartPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

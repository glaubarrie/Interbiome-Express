package dev.knoppid.ibe;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client (mod clients only): speed in b/s x100 and fuel progress in permille, for the cart screen. */
public record StatePayload(int speed, int burn) implements CustomPacketPayload {
	public static final Type<StatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ExpressMod.ID, "state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, StatePayload::speed, ByteBufCodecs.VAR_INT, StatePayload::burn, StatePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

package dev.knoppid.ibe;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Empty packet. Client -> server: "open the cart's inventory" (E key). Its registration also tells the server the client has the mod. */
public record OpenPayload() implements CustomPacketPayload {
	public static final Type<OpenPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ExpressMod.ID, "open"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenPayload> CODEC = StreamCodec.unit(new OpenPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

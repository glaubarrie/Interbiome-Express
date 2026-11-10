package dev.knoppid.ibe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * State and driving logic of an Express-Minecart. It is attached to a plain vanilla minecart
 * (so vanilla clients see a normal minecart); the server moves the cart itself every tick.
 * Slots 0-2 = furnaces, 3-17 = fuel. W = go, S = stop.
 */
public class ExpressCart {
	public static final int FURNACES = 3, SLOTS = 18;

	public static final Codec<ExpressCart> CODEC = RecordCodecBuilder.create(i -> i.group(
			ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(c -> c.inv.getItems()),
			Codec.INT.optionalFieldOf("burn", 0).forGetter(c -> c.burn),
			Codec.INT.optionalFieldOf("total", 0).forGetter(c -> c.burnTotal)).apply(i, ExpressCart::new));

	public final SimpleContainer inv = new SimpleContainer(SLOTS);
	public Minecart m;
	private int burn, burnTotal;
	private boolean go, wasFwd, wasBack, onActivator, curve, steerL, steerR;
	private double speed, slopeY;                    // blocks per tick
	private Vec3 heading = Vec3.ZERO, at, pending;            // horizontal unit vector; where we left the cart last tick
	private BlockPos rail, last;                  // current rail block and the one we came from

	public ExpressCart() { }

	private ExpressCart(List<ItemStack> items, int burn, int total) {
		for (int i = 0; i < Math.min(SLOTS, items.size()); i++) inv.setItem(i, items.get(i));
		this.burn = burn;
		this.burnTotal = total;
	}

	/** Synced to the menu: 0 = speed in b/s x100, 1 = burn progress in permille. */
	public ContainerData menuData() {
		return new ContainerData() {
			@Override public int get(int i) { return i == 0 ? (int) Math.round(speed * 2000) : burnTotal > 0 ? Math.max(0, burn) * 1000 / burnTotal : 0; }
			@Override public void set(int i, int v) { }
			@Override public int getCount() { return 2; }
		};
	}

	public boolean hasContents() {
		for (ItemStack s : inv.getItems()) if (!s.isEmpty()) return true;
		return false;
	}

	public void restore(ItemContainerContents contents) {
		contents.copyInto(inv.getItems());
		inv.setChanged();
	}

	/** Called every server tick. Undoes vanilla's own movement of the cart, then drives it ourselves. */
	public void tick(Minecart cart, ServerLevel level) {
		m = cart;
		if (at != null && at.distanceToSqr(m.position()) < 25.0) m.setPos(at);
		drive(level);
		at = m.position();
	}

	private void drive(ServerLevel level) {
		IbeConfig cfg = IbeConfig.c;

		boolean fwd = false, back = false;   // W / S edges toggle go / stop
		if (m.getFirstPassenger() instanceof ServerPlayer p) {
			Input in = p.getLastClientInput();
			fwd = in.forward();
			back = in.backward();
			steerL = in.left() && !in.right();   // A / D choose the way at rail junctions
			steerR = in.right() && !in.left();
			if (fwd && !wasFwd) {
				Vec3 look = p.getLookAngle();
				Vec3 h = new Vec3(look.x, 0, look.z);
				if (h.lengthSqr() > 1.0E-4) {
					h = h.normalize();
					if (speed < 0.01) heading = h;
					else if (h.dot(heading) < 0) pending = h;   // facing the wrong way: brake, then drive the other way
				}
				go = true;
			}
			if (back && !wasBack) { go = false; pending = null; }
		} else {
			go = false;
			pending = null;
			steerL = steerR = false;
		}
		wasFwd = fwd;
		wasBack = back;

		if (rail == null || shape(level) == null) rail = findRail(level);
		if (rail == null) {   // no rail: fall
			speed = 0;
			go = false;
			m.setDeltaMovement(0, Math.max(m.getDeltaMovement().y - 0.04, -2.0), 0);
			m.move(MoverType.SELF, m.getDeltaMovement());
			if (m.onGround()) m.setDeltaMovement(Vec3.ZERO);
			return;
		}

		if (cfg.activatorRails && rail != null) {   // powered activator rail = the S key (brakes normally, no instant stop)
			BlockState rs = level.getBlockState(rail);
			boolean powered = rs.is(Blocks.ACTIVATOR_RAIL) && rs.getValue(BlockStateProperties.POWERED);
			if (powered && !onActivator) { go = false; pending = null; }
			onActivator = powered;
		}

		int furnaces = 0;
		double base = 0;
		for (int i = 0; i < FURNACES; i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(Items.FURNACE)) { furnaces++; base += cfg.normalFurnaceSpeed; }
			else if (s.is(Items.BLAST_FURNACE)) { furnaces++; base += cfg.blastFurnaceSpeed; }
		}
		if (furnaces == 0 || (go && burn <= 0 && !refuel(furnaces))) go = false;

		double target = 0;
		if (go) {
			target = base + (slopeY > 0.05 ? -cfg.slopeSpeedChange : slopeY < -0.05 ? cfg.slopeSpeedChange : 0);
			target = Math.max(curve ? target * cfg.curveSpeedFactor : target, 1.0);
		}
		if (pending != null) {   // turning around: stop first, then go the other way
			target = 0;
			if (speed < 0.01) { heading = pending; pending = null; last = null; }
		}
		double t = target / 20.0;
		speed = speed < t ? Math.min(t, speed + cfg.acceleration / 400.0) : Math.max(t, speed - cfg.brakeDeceleration / 400.0);
		if (burn > 0) burn--;   // a lit fuel item always burns down, even when stopped or abandoned

		if (speed > 1.0E-4) {
			travel(level, speed);
		} else {
			speed = 0;
			m.setDeltaMovement(Vec3.ZERO);
			snap(level);
		}
	}

	private boolean refuel(int furnaces) {
		for (int i = FURNACES; i < SLOTS; i++) {
			ItemStack s = inv.getItem(i);
			int ticks = burnTicks(s);
			if (ticks <= 0) continue;
			burnTotal = burn = Math.max(1, (int) (ticks / furnaces / IbeConfig.c.burnTimeDivisor));
			if (s.is(Items.LAVA_BUCKET)) inv.setItem(i, new ItemStack(Items.BUCKET)); else s.shrink(1);
			inv.setChanged();
			return true;
		}
		return false;
	}

	/** Stopgap: vanilla burn times of common fuels, 300 for everything else. */
	private static int burnTicks(ItemStack s) {
		if (s.isEmpty() || !s.has(DataComponents.COOKING_FUEL)) return 0;
		if (s.is(Items.LAVA_BUCKET)) return 20000;
		if (s.is(Items.COAL_BLOCK)) return 16000;
		if (s.is(Items.DRIED_KELP_BLOCK)) return 4001;
		if (s.is(Items.BLAZE_ROD)) return 2400;
		if (s.is(Items.COAL) || s.is(Items.CHARCOAL)) return 1600;
		return s.is(Items.STICK) ? 100 : 300;
	}

	// ---- rail following

	private static boolean isRail(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getBlock() instanceof BaseRailBlock;
	}

	private BlockPos findRail(ServerLevel level) {
		BlockPos b = m.blockPosition();
		return isRail(level, b) ? b : isRail(level, b.below()) ? b.below() : null;
	}

	/** Rail ends of the current rail as {x,y,z, x,y,z} offsets (vanilla table), or null if it is gone. */
	private int[] shape(ServerLevel level) { return shapeAt(level, rail); }

	private static int[] shapeAt(ServerLevel level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		if (!(st.getBlock() instanceof BaseRailBlock rb)) return null;
		return switch (st.getValue(rb.getShapeProperty())) {
			case NORTH_SOUTH -> new int[]{0, 0, -1, 0, 0, 1};
			case EAST_WEST -> new int[]{-1, 0, 0, 1, 0, 0};
			case ASCENDING_EAST -> new int[]{-1, 0, 0, 1, 1, 0};
			case ASCENDING_WEST -> new int[]{-1, 1, 0, 1, 0, 0};
			case ASCENDING_NORTH -> new int[]{0, 1, -1, 0, 0, 1};
			case ASCENDING_SOUTH -> new int[]{0, 0, -1, 0, 1, 1};
			case SOUTH_EAST -> new int[]{0, 0, 1, 1, 0, 0};
			case SOUTH_WEST -> new int[]{0, 0, 1, -1, 0, 0};
			case NORTH_WEST -> new int[]{0, 0, -1, -1, 0, 0};
			case NORTH_EAST -> new int[]{0, 0, -1, 1, 0, 0};
		};
	}

	private Vec3 end(int[] e, int o) {
		return new Vec3(rail.getX() + 0.5 + e[o] * 0.5, rail.getY() + 0.0625 + e[o + 1], rail.getZ() + 0.5 + e[o + 2] * 0.5);
	}

	/** Stopped cart: sit exactly on the rail line (fixes the off-center seat on curves) and face along it. */
	private void snap(ServerLevel level) {
		int[] e = shape(level);
		if (e == null) return;
		Vec3 a = end(e, 0), seg = end(e, 3).subtract(a), p = m.position();
		Vec3 q = a.add(seg.scale(Mth.clamp(p.subtract(a).dot(seg) / seg.lengthSqr(), 0.0, 1.0)));
		if (q.distanceToSqr(p) < 1.0E-4) return;
		m.setPos(q);
		m.setYRot((float) Math.toDegrees(Math.atan2(seg.z, seg.x)));
		m.setXRot((float) Math.toDegrees(Math.atan2(seg.y, Math.hypot(seg.x, seg.z))));
	}

	private static Vec3 leftOf(Vec3 h) { return new Vec3(h.z, 0, -h.x); }

	private static Vec3 rightOf(Vec3 h) { return new Vec3(-h.z, 0, h.x); }

	/** A side rail next to the current rail whose end points at it (a T-junction), or null. */
	private BlockPos sideRail(ServerLevel level, int dx, int dz) {
		BlockPos c = rail.offset(dx, 0, dz);
		if (!isRail(level, c)) c = c.below();
		if (!isRail(level, c) || c.equals(last)) return null;
		int[] e = shapeAt(level, c);
		if (e == null) return null;
		return (e[0] == -dx && e[2] == -dz) || (e[3] == -dx && e[5] == -dz) ? c : null;
	}

	private void travel(ServerLevel level, double dist) {
		Vec3 p = m.position(), dir = null;
		for (int i = 0; i < 24 && dist > 1.0E-6 && rail != null; i++) {
			int[] e = shape(level);
			if (e == null) { rail = null; break; }
			Vec3 a = end(e, 0), b = end(e, 3);
			double along = heading.x * (b.x - a.x) + heading.z * (b.z - a.z);
			boolean toB = along >= 0;
			boolean side = IbeConfig.c.junctions && (steerL || steerR);
			if (side && Math.abs(along) < 1.0E-6) {   // entering a rail from the side: A / D pick the direction
				Vec3 sv = steerL ? leftOf(heading) : rightOf(heading);
				toB = sv.x * (b.x - a.x) + sv.z * (b.z - a.z) >= 0;
			}
			Vec3 from = toB ? a : b, to = toB ? b : a, seg = to.subtract(from);
			int o = toB ? 3 : 0;
			double t = Mth.clamp(p.subtract(from).dot(seg) / seg.lengthSqr(), 0.0, 1.0);
			double left = seg.length() * (1.0 - t);
			dir = seg.normalize();
			curve = e[0] != -e[3] || e[2] != -e[5];
			if (seg.x != 0 || seg.z != 0) heading = new Vec3(seg.x, 0, seg.z).normalize();
			if (side && Math.abs(along) > 1.0E-6 && e[1] == 0 && e[4] == 0 && e[0] == -e[3] && e[2] == -e[5] && t <= 0.5 + 1.0E-6) {   // junction on a flat straight rail
				double toCenter = seg.length() * (0.5 - t);
				Vec3 sv = steerL ? leftOf(dir) : rightOf(dir);
				BlockPos c = dist >= toCenter ? sideRail(level, (int) Math.round(sv.x), (int) Math.round(sv.z)) : null;
				if (c != null) {   // turn at the middle of this rail block
					dist = Math.max(0.0, dist - toCenter - 0.5);
					p = from.add(seg.scale(0.5));
					heading = new Vec3(Math.round(sv.x), 0, Math.round(sv.z));
					last = rail;
					rail = c;
					continue;
				}
			}
			if (dist <= left) {
				p = from.add(seg.scale(t)).add(dir.scale(dist));
				break;
			}
			dist -= left;
			p = to;
			BlockPos n = rail.offset(e[o], e[o + 1], e[o + 2]);
			BlockPos next = isRail(level, n) ? n : isRail(level, n.below()) ? n.below() : null;
			if (next == null) {   // end of the line: stop just before the edge
				p = to.subtract(dir.scale(0.02));
				speed = 0;
				go = false;
				break;
			}
			last = rail;
			rail = next;
			if (IbeConfig.c.junctions) {   // junction rules for the rail we just entered
				int[] ne = shape(level);
				if (ne != null && ne[1] == 0 && ne[4] == 0) {
					Vec3 na = end(ne, 0), nb = end(ne, 3);
					double al = heading.x * (nb.x - na.x) + heading.z * (nb.z - na.z);
					boolean straightX = ne[0] == -ne[3] && ne[2] == -ne[5];
					int ndx = (int) Math.round(heading.x), ndz = (int) Math.round(heading.z);
					BlockPos y = null;
					int o2 = al >= 0 ? 3 : 0;   // the exit this rail would send us to by itself
					boolean ownIsStraight = ne[o2] == ndx && ne[o2 + 2] == ndz;
					if (!steerL && !steerR) {
						if (!ownIsStraight) y = sideRail(level, ndx, ndz);   // no key: drive straight over a crossing if a rail continues
					} else if (!straightX) {
						Vec3 sv = steerL ? leftOf(heading) : rightOf(heading);   // curve with a branch: A / D pick a side that is not the curve
						ndx = (int) Math.round(sv.x);
						ndz = (int) Math.round(sv.z);
						if (ne[o2] != ndx || ne[o2 + 2] != ndz) y = sideRail(level, ndx, ndz);
					}
					if (y != null) {
						dist = Math.max(0.0, dist - 1.0);
						heading = new Vec3(ndx, 0, ndz);
						last = rail;
						rail = y;
						continue;
					}
				}
			}
		}
		m.setPos(p);
		if (dir == null || speed == 0) { m.setDeltaMovement(Vec3.ZERO); return; }
		slopeY = dir.y;
		m.setDeltaMovement(dir.scale(speed));
		float ty = (float) Math.toDegrees(Math.atan2(dir.z, dir.x));
		float tx = (float) Math.toDegrees(Math.atan2(dir.y, Math.hypot(dir.x, dir.z)));
		boolean smooth = IbeConfig.c.curveSmoothing;
		m.setYRot(smooth ? Mth.rotLerp(0.35F, m.getYRot(), ty) : ty);
		m.setXRot(smooth ? Mth.lerp(0.35F, m.getXRot(), tx) : tx);
	}
}

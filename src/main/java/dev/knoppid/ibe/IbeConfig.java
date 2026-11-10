package dev.knoppid.ibe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** config/interbiome-express.json (reload with /ibe reload). Speeds are blocks per second. */
public final class IbeConfig {
	public double normalFurnaceSpeed = 8.0;
	public double blastFurnaceSpeed = 16.0;
	public double burnTimeDivisor = 8.0;     // burn time per item = vanilla time / furnaces / this
	public double acceleration = 16.0;
	public double brakeDeceleration = 16.0;
	public double slopeSpeedChange = 4.0;    // uphill minus, downhill plus
	public double curveSpeedFactor = 1.0;    // 1.0 = no slowdown in curves
	public boolean curveSmoothing = true;
	public boolean activatorRails = true;    // a powered activator rail brakes the cart like pressing S
	public boolean junctions = true;         // hold A / D at a rail junction to turn left / right

	public static IbeConfig c = new IbeConfig();

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("interbiome-express.json");
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		try {
			if (Files.exists(file)) {
				IbeConfig loaded = gson.fromJson(Files.readString(file), IbeConfig.class);
				if (loaded != null) c = loaded;
			}
			Files.writeString(file, gson.toJson(c));
		} catch (Exception e) {
			ExpressMod.LOG.warn("Config error: {}", e.toString());
		}
	}
}

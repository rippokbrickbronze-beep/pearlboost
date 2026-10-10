package pearlboost;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public class PearlBoostClient implements ClientModInitializer {
    static final double SPEED = 1.5;       // pearl and wind charge launch speed
    static final int MAX_FLIGHT = 25;      // shorter flights = less random spread
    static final int MIN_FLIGHT = 3;       // ignore intercepts sooner than this
    static final double MIN_DIST = 3.0;    // ...or closer than this (stops self-boost)
    static final double MAX_SPEED_ERR = 0.05; // skip intercepts the charge can't reach accurately
    static final int WAIT_LIMIT = 30;      // ticks to wait for cooldowns

    static KeyMapping key;
    static boolean armed = false;
    static int armTicks = 0;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("pearlboost", "main"));
        key = KeyBindingHelper.registerKeyBinding(
            new KeyMapping("key.pearlboost.run", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_TAB, cat));
        ClientTickEvents.END_CLIENT_TICK.register(PearlBoostClient::tick);
    }

    static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || mc.level == null) { armed = false; return; }

        while (key.consumeClick()) { armed = true; armTicks = WAIT_LIMIT; }
        if (!armed) return;

        int pearl = hotbarSlot(p, Items.ENDER_PEARL);
        int wind = hotbarSlot(p, Items.WIND_CHARGE);
        if (pearl < 0 || wind < 0) {
            p.displayClientMessage(Component.literal("PearlBoost: need a pearl and a wind charge in the hotbar"), true);
            armed = false;
            return;
        }
        // wait until both items are off cooldown
        if (p.getCooldowns().isOnCooldown(p.getInventory().getItem(pearl))
                || p.getCooldowns().isOnCooldown(p.getInventory().getItem(wind))) {
            if (--armTicks <= 0) armed = false;
            return;
        }
        armed = false;
        fire(mc, p, pearl, wind);
    }

    static void fire(Minecraft mc, LocalPlayer p, int pearl, int wind) {
        float savedYaw = p.getYRot(), savedPitch = p.getXRot();
        int savedSlot = p.getInventory().getSelectedSlot();

        Vec3 look = p.getLookAngle();
        Vec3 m = p.getDeltaMovement();
        Vec3 v = new Vec3(m.x, p.onGround() ? 0 : m.y, m.z);   // momentum thrown items inherit

        // the two projectiles spawn at different heights
        Vec3 pearlOrigin = p.getEyePosition().add(0, -0.1, 0);
        Vec3 windOrigin = p.getEyePosition();

        // predict the pearl's flight
        Vec3[] path = new Vec3[MAX_FLIGHT + 1];
        Vec3 pos = pearlOrigin, vel = look.scale(SPEED).add(v);
        path[0] = pos;
        for (int t = 1; t <= MAX_FLIGHT; t++) {
            pos = pos.add(vel);
            vel = vel.scale(0.99).add(0, -0.03, 0);
            path[t] = pos;
        }

        // find the wind-charge direction that meets the pearl (wind charge has no gravity or drag)
        Vec3 bestDir = null;
        double bestErr = Double.MAX_VALUE;
        for (int k = MIN_FLIGHT; k <= MAX_FLIGHT; k++) {
            if (path[k].distanceTo(windOrigin) < MIN_DIST) continue;
            Vec3 need = path[k].subtract(windOrigin).scale(1.0 / k).subtract(v);   // = SPEED * dir
            double err = Math.abs(need.length() - SPEED);
            if (err < bestErr) { bestErr = err; bestDir = need.normalize(); }
        }
        if (bestErr > MAX_SPEED_ERR) bestDir = null;   // no accurate intercept for this throw

        // 1) pearl along the crosshair
        p.getInventory().setSelectedSlot(pearl);
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);

        // 2) wind charge at the predicted meeting point, same tick
        if (bestDir != null) {
            double horiz = Math.sqrt(bestDir.x * bestDir.x + bestDir.z * bestDir.z);
            p.setYRot((float) Math.toDegrees(Math.atan2(-bestDir.x, bestDir.z)));
            p.setXRot((float) -Math.toDegrees(Math.atan2(bestDir.y, horiz)));
            p.getInventory().setSelectedSlot(wind);
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        } else {
            p.displayClientMessage(Component.literal("PearlBoost: no accurate intercept, aim differently"), true);
        }

        p.setYRot(savedYaw);
        p.setXRot(savedPitch);
        p.getInventory().setSelectedSlot(savedSlot);
    }

    static int hotbarSlot(LocalPlayer p, Item item) {
        for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).is(item)) return i;
        return -1;
    }
}

package pearlboost;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;

/**
 * Press the key (default TAB):
 *   tick 0: throw an ender pearl where you are looking
 *   tick N: turn toward the pearl, throw a wind charge, turn back
 * Rotation is NOT hidden: the server sees exactly what your client shows.
 * Intended for singleplayer / your own server / servers that allow it.
 */
public class PearlBoostClient implements ClientModInitializer {
    /** Ticks to wait between the pearl and the wind charge. Tune this. */
    static final int DELAY_TICKS = 2;

    static KeyMapping key;
    static int stage = 0;      // 0 idle, 1 waiting for wind charge
    static int wait = 0;
    static float savedYaw, savedPitch;
    static int savedSlot;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("pearlboost", "main"));
        key = KeyBindingHelper.registerKeyBinding(
            new KeyMapping("key.pearlboost.run", InputConstants.Type.KEYSYM, GLFW_TAB, cat));
        ClientTickEvents.END_CLIENT_TICK.register(PearlBoostClient::tick);
    }

    static final int GLFW_TAB = GLFW.GLFW_KEY_TAB;

    static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || mc.level == null) { stage = 0; return; }

        if (stage == 0) {
            while (key.consumeClick()) {
                int pearl = hotbarSlot(p, Items.ENDER_PEARL);
                int wind = hotbarSlot(p, Items.WIND_CHARGE);
                if (pearl < 0 || wind < 0) {
                    p.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "PearlBoost: need an ender pearl and a wind charge in the hotbar"), true);
                    return;
                }
                savedSlot = p.getInventory().getSelectedSlot();
                savedYaw = p.getYRot();
                savedPitch = p.getXRot();
                p.getInventory().setSelectedSlot(pearl);
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);   // pearl, along your crosshair
                stage = 1;
                wait = DELAY_TICKS;
            }
            return;
        }

        if (--wait > 0) return;

        Entity pearl = mc.level.getEntities((Entity) null, p.getBoundingBox().inflate(16),
                e -> e.getType() == EntityType.ENDER_PEARL)
            .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(p))).orElse(null);

        int wind = hotbarSlot(p, Items.WIND_CHARGE);
        if (pearl != null && wind >= 0) {
            Vec3 eye = p.getEyePosition();
            Vec3 d = pearl.position().subtract(eye);
            double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
            p.setYRot((float) (Math.toDegrees(Math.atan2(-d.x, d.z))));
            p.setXRot((float) (-Math.toDegrees(Math.atan2(d.y, horiz))));
            p.getInventory().setSelectedSlot(wind);
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);        // wind charge at the pearl
        }
        p.setYRot(savedYaw);
        p.setXRot(savedPitch);
        p.getInventory().setSelectedSlot(savedSlot);
        stage = 0;
    }

    static int hotbarSlot(LocalPlayer p, Item item) {
        for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).is(item)) return i;
        return -1;
    }
}

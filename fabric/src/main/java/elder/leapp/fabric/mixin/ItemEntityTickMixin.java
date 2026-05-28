package elder.leapp.fabric.mixin;

// ItemEntityTickMixin.java
// Detects when a written book item entity is ticked while inside a LeapPortalBlock.
// When that happens, reads the book's text content, passes it to AddressParser,
// and if a valid address is found, links the portal to that address.
//
// If the book has no valid address: sends "This book doesn't contain a valid address."
// and returns the book to the player (or drops it at the portal if no player is near).
// If the target address is the same as this world's address: sends "You're already here."
// and returns the book.
//
// Targets ItemEntity.tick() — runs every game tick for each item entity in the world.

import elder.leapp.LeapPadCommon;
import elder.leapp.portal.AddressParser;
import elder.leapp.portal.LeapPortalBlock;
import elder.leapp.portal.PortalRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public class ItemEntityTickMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void leappad_onTick(CallbackInfo ci) {
        ItemEntity self = (ItemEntity)(Object)this;

        // Server side only
        if (self.level().isClientSide()) return;

        // Only care about written books
        ItemStack stack = self.getItem();
        if (!stack.is(Items.WRITTEN_BOOK)) return;

        // Check if this item is inside a LeapPortalBlock
        BlockPos pos = BlockPos.containing(self.getX(), self.getY(), self.getZ());
        BlockState blockAtPos = self.level().getBlockState(pos);
        if (!(blockAtPos.getBlock() instanceof LeapPortalBlock)) return;

        // Get the portal UUID for this block position
        String portalUuid = PortalRegistry.getUuidForPos(pos);
        if (portalUuid == null) return;

        // Remove the item entity immediately — we handle it from here
        self.discard();

        // Extract text from the written book's NBT
        String bookText = extractBookText(stack);

        // Parse for a valid address
        AddressParser.ParsedAddress parsed = AddressParser.parse(bookText);

        // Find the nearest player to send feedback messages to
        ServerPlayer nearbyPlayer = findNearestPlayer((ServerLevel) self.level(), pos);

        if (parsed == null) {
            // No valid address found — return book to player or drop it
            sendMessage(nearbyPlayer, "This book doesn't contain a valid address.");
            returnBook(self, stack, nearbyPlayer);
            return;
        }

        // Check if the target is this world itself
        String thisAddress = PortalRegistry.getThisWorldAddress();
        if (!thisAddress.isEmpty() && parsed.full.equals(thisAddress)) {
            sendMessage(nearbyPlayer, "You're already here.");
            returnBook(self, stack, nearbyPlayer);
            return;
        }

        // Valid address — link the portal
        PortalRegistry.linkPortal(portalUuid, parsed.full);
        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Portal {} linked to {} by thrown book.",
            portalUuid, parsed.full
        );

        if (nearbyPlayer != null) {
            nearbyPlayer.sendSystemMessage(
                Component.literal("Portal linked to " + parsed.full + ".")
            );
        }
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    // Extracts all page text from a written book's NBT into one concatenated string
    private String extractBookText(ItemStack stack) {
        if (!stack.hasTag()) return "";
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        if (!tag.contains("pages")) return "";

        StringBuilder sb = new StringBuilder();
        net.minecraft.nbt.ListTag pages = tag.getList("pages",
            net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < pages.size(); i++) {
            // Pages are stored as JSON text components — extract the plain string
            String pageJson = pages.getString(i);
            try {
                Component component = Component.Serializer.fromJson(pageJson);
                if (component != null) sb.append(component.getString());
            } catch (Exception e) {
                // If JSON parsing fails, try the raw string
                sb.append(pageJson);
            }
            sb.append(" ");
        }
        return sb.toString();
    }

    // Returns the nearest ServerPlayer within 8 blocks of the portal, or null
    private ServerPlayer findNearestPlayer(ServerLevel level, BlockPos pos) {
        double nearest = Double.MAX_VALUE;
        ServerPlayer result = null;
        for (ServerPlayer player : level.players()) {
            double dist = player.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5
            );
            if (dist < 64.0 && dist < nearest) { // 64 = 8 blocks squared
                nearest = dist;
                result = player;
            }
        }
        return result;
    }

    // Sends a system message to a player if one is available
    private void sendMessage(ServerPlayer player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }

    // Returns the book item to the nearest player's inventory,
    // or drops it at the portal position if no player is found
    private void returnBook(ItemEntity original, ItemStack stack, ServerPlayer player) {
        if (player != null) {
            // Try to add to inventory; drop at feet if inventory is full
            if (!player.getInventory().add(stack.copy())) {
                player.drop(stack.copy(), false);
            }
        } else {
            // No nearby player — spawn a new item entity at the portal position
            ItemEntity drop = new ItemEntity(
                original.level(),
                original.getX(), original.getY(), original.getZ(),
                stack.copy()
            );
            original.level().addFreshEntity(drop);
        }
    }
}

package portprobe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

public class ProbeItem extends Item {
    private static final int MAX_SLOT_LINES = 6;
    private static final int MAX_ACCEPTED_NAMES = 6;
    // Larger inventories are reported by slot count only; scanning them could stall the server tick.
    private static final int MAX_TESTED_SLOTS = 4096;
    private static final int HOLD_GUARD_TICKS = 8;
    // Arbitrary items used to tell "accepts anything" from "filtered" without relying on the player's inventory.
    private static final List<Item> SAMPLE_ITEMS = List.of(Items.COBBLESTONE, Items.STICK, Items.FEATHER);
    private static final List<Fluid> SAMPLE_FLUIDS = List.of(Fluids.WATER, Fluids.LAVA);
    private static final Set<BlockCapability<?, ?>> READ_IN_DETAIL = Set.of(
            Capabilities.Item.BLOCK, Capabilities.Fluid.BLOCK, Capabilities.Energy.BLOCK);

    public ProbeItem(Properties properties) {
        super(properties);
    }

    // onItemUseFirst runs before the block's own right-click, so the probe reads a machine
    // instead of opening its GUI.
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            // Restarted on every attempt, so holding the button reads once instead of repeating.
            boolean held = player.getCooldowns().isOnCooldown(stack);
            player.getCooldowns().addCooldown(stack, HOLD_GUARD_TICKS);
            if (held) {
                return InteractionResult.CONSUME;
            }
            BlockPos pos = context.getClickedPos();
            Component block = level.getBlockState(pos).getBlock().getName();
            if (player.isShiftKeyDown()) {
                player.sendSystemMessage(header(block, tr("all_sides")));
                for (Direction side : Direction.values()) {
                    player.sendSystemMessage(summary(level, pos, side));
                }
                player.sendSystemMessage(summary(level, pos, null));
            } else {
                Direction side = context.getClickedFace();
                player.sendSystemMessage(header(block, sideName(side)));
                for (Component line : details(level, pos, side, player)) {
                    player.sendSystemMessage(line);
                }
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(tr("tooltip.use").withStyle(ChatFormatting.GRAY));
        tooltip.accept(tr("tooltip.sneak").withStyle(ChatFormatting.GRAY));
        tooltip.accept(tr("tooltip.test").withStyle(ChatFormatting.GRAY));
    }

    /** Runs a transfer inside a transaction that is rolled back, and returns the amount it would have moved. */
    private static int simulate(ToIntFunction<Transaction> transfer) {
        try (Transaction transaction = Transaction.openRoot()) {
            return transfer.applyAsInt(transaction);
        }
    }

    private static <T extends Resource> boolean canExtract(ResourceHandler<T> handler, int index) {
        T content = handler.getResource(index);
        // Everything is asked for: some handlers only give whole increments (a cauldron gives nothing for 1 mB).
        return !content.isEmpty() && simulate(tx -> handler.extract(index, content, Integer.MAX_VALUE, tx)) > 0;
    }

    /** Each distinct item (components included) the player carries, hotbar first, without the probe itself. */
    private static List<ItemResource> carriedItems(Player player) {
        List<ItemStack> main = player.getInventory().getNonEquipmentItems();
        int hotbar = Inventory.getSelectionSize();
        List<ItemStack> ordered = new ArrayList<>(main.subList(0, hotbar));
        ordered.add(player.getOffhandItem());
        ordered.addAll(main.subList(hotbar, main.size()));

        Set<ItemResource> distinct = new LinkedHashSet<>();
        for (ItemStack stack : ordered) {
            if (!stack.isEmpty() && !(stack.getItem() instanceof ProbeItem)) {
                distinct.add(ItemResource.of(stack));
            }
        }
        return List.copyOf(distinct);
    }

    /**
     * Why the item contents must be left alone, or null. Reading an unopened loot container would
     * roll its loot, and a locked one is not this player's to look into.
     */
    private static @Nullable Component notReadReason(Level level, BlockPos pos, Player player) {
        List<BlockPos> parts = new ArrayList<>(List.of(pos));
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            parts.add(pos.relative(ChestBlock.getConnectedDirection(state)));
        }
        for (BlockPos part : parts) {
            BlockEntity entity = level.getBlockEntity(part);
            if (entity instanceof RandomizableContainer loot && loot.getLootTable() != null) {
                return tr("items.loot").withStyle(ChatFormatting.GRAY);
            }
            if (entity instanceof BaseContainerBlockEntity container && !container.canOpen(player)) {
                return tr("items.locked").withStyle(ChatFormatting.GRAY);
            }
        }
        return null;
    }

    private static List<Component> details(Level level, BlockPos pos, Direction side, Player player) {
        List<Component> lines = new ArrayList<>();
        List<ItemResource> carried = carriedItems(player);

        ResourceHandler<ItemResource> items = level.getCapability(Capabilities.Item.BLOCK, pos, side);
        Component notRead = items == null ? null : notReadReason(level, pos, player);
        if (items == null) {
            lines.add(tr("items.none").withStyle(ChatFormatting.GRAY));
        } else if (notRead != null) {
            lines.add(notRead);
        } else {
            addItemLines(lines, items, carried);
        }

        ResourceHandler<FluidResource> fluids = level.getCapability(Capabilities.Fluid.BLOCK, pos, side);
        if (fluids == null) {
            lines.add(tr("fluids.none").withStyle(ChatFormatting.GRAY));
        } else {
            addFluidLines(lines, fluids, carried);
        }

        EnergyHandler energy = level.getCapability(Capabilities.Energy.BLOCK, pos, side);
        if (energy == null) {
            lines.add(tr("energy.none").withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(tr("energy", energy.getAmountAsLong(), energy.getCapacityAsLong(),
                    yesNo(simulate(tx -> energy.insert(Integer.MAX_VALUE, tx)) > 0),
                    yesNo(simulate(tx -> energy.extract(Integer.MAX_VALUE, tx)) > 0)));
        }

        List<Component> other = otherCapabilities(level, pos, side, true);
        if (!other.isEmpty()) {
            lines.add(tr("other", ComponentUtils.formatList(other, Component.literal(", "))));
        }
        return lines;
    }

    /**
     * IDs of every other block capability, from any mod, that answers on this side. Capabilities
     * that take no side are only included on request, so the all-sides summary lists them once.
     */
    @SuppressWarnings("unchecked")
    private static List<Component> otherCapabilities(Level level, BlockPos pos, @Nullable Direction side,
            boolean withUnsided) {
        List<String> ids = new ArrayList<>();
        for (BlockCapability<?, ?> capability : BlockCapability.getAll()) {
            if (READ_IN_DETAIL.contains(capability)) {
                continue;
            }
            Object found = null;
            if (capability.contextClass() == Direction.class) {
                found = level.getCapability((BlockCapability<?, Direction>) capability, pos, side);
            } else if (capability.contextClass() == Void.class && withUnsided) {
                found = level.getCapability((BlockCapability<?, Void>) capability, pos, null);
            }
            if (found != null) {
                ids.add(capability.name().toString());
            }
        }
        return ids.stream().sorted().<Component>map(Component::literal).toList();
    }

    private static void addItemLines(List<Component> lines, ResourceHandler<ItemResource> items,
            List<ItemResource> carried) {
        int slots = items.size();
        if (slots > MAX_TESTED_SLOTS) {
            lines.add(tr("items.too_large", slots).withStyle(ChatFormatting.GRAY));
            return;
        }
        int occupied = 0;
        boolean canExtract = false;
        for (int slot = 0; slot < slots; slot++) {
            if (!items.getResource(slot).isEmpty()) {
                occupied++;
                canExtract = canExtract || canExtract(items, slot);
            }
        }
        lines.add(tr("items", slots, occupied, yesNo(canExtract)));

        List<ItemResource> samples = SAMPLE_ITEMS.stream().map(ItemResource::of).toList();

        // Small inventories list every slot; large ones only the occupied slots, capped.
        boolean listEmptySlots = slots <= MAX_SLOT_LINES;
        int listed = 0;
        for (int slot = 0; slot < slots && listed < MAX_SLOT_LINES; slot++) {
            ItemResource content = items.getResource(slot);
            if (content.isEmpty() && !listEmptySlots) {
                continue;
            }
            Component what = content.isEmpty() ? tr("empty")
                    : tr("stack", content.getHoverName(), items.getAmountAsInt(slot));
            lines.add(tr("slot", slot + 1, what, slotRole(items, slot, content, carried, samples)));
            listed++;
        }
        if (!listEmptySlots && occupied > listed) {
            lines.add(tr("slot.more", occupied - listed).withStyle(ChatFormatting.GRAY));
        }

        lines.add(tr("accepts", acceptedItems(items, carried, samples, slots > 0 && occupied == slots)));
    }

    private static Component slotRole(ResourceHandler<ItemResource> items, int slot, ItemResource content,
            List<ItemResource> carried, List<ItemResource> samples) {
        List<ItemResource> candidates = new ArrayList<>();
        if (!content.isEmpty()) {
            candidates.add(content);
        }
        candidates.addAll(carried);
        candidates.addAll(samples);

        boolean in = false;
        for (ItemResource candidate : candidates) {
            if (simulate(tx -> items.insert(slot, candidate, 1, tx)) > 0) {
                in = true;
                break;
            }
        }
        boolean out = canExtract(items, slot);

        if (in && out) {
            return tr("role.both");
        } else if (in) {
            return tr("role.in");
        } else if (out) {
            return tr("role.out");
        }
        // An empty slot cannot show whether it is an output, and a full one cannot show whether it is an input.
        return content.isEmpty() ? tr("role.no_input") : tr("role.locked");
    }

    private static Component acceptedItems(ResourceHandler<ItemResource> items, List<ItemResource> carried,
            List<ItemResource> samples, boolean full) {
        List<Component> accepted = new ArrayList<>();
        boolean allCarried = true;
        for (ItemResource item : carried) {
            if (simulate(tx -> items.insert(item, 1, tx)) == 0) {
                allCarried = false;
            } else if (!accepted.contains(item.getHoverName())) {
                accepted.add(item.getHoverName());
            }
        }
        long samplesAccepted = samples.stream()
                .filter(sample -> simulate(tx -> items.insert(sample, 1, tx)) > 0)
                .count();

        if (allCarried && samplesAccepted == samples.size()) {
            return tr("accepts.any").withStyle(ChatFormatting.GREEN);
        }
        if (!accepted.isEmpty()) {
            return nameList(accepted).withStyle(ChatFormatting.GREEN);
        }
        if (samplesAccepted > 0) {
            return tr("accepts.not_carried").withStyle(ChatFormatting.YELLOW);
        }
        return tr(full ? "accepts.full" : "accepts.none").withStyle(ChatFormatting.RED);
    }

    private static void addFluidLines(List<Component> lines, ResourceHandler<FluidResource> fluids,
            List<ItemResource> carried) {
        int tanks = fluids.size();

        // Same rule as item slots: few tanks list all of them, many only the filled ones, capped.
        boolean listEmptyTanks = tanks <= MAX_SLOT_LINES;
        List<Component> tankLines = new ArrayList<>();
        boolean canDrain = false;
        int filled = 0;
        for (int tank = 0; tank < tanks; tank++) {
            FluidResource fluid = fluids.getResource(tank);
            if (!fluid.isEmpty()) {
                filled++;
                canDrain = canDrain || canExtract(fluids, tank);
            }
            if (tankLines.size() == MAX_SLOT_LINES || (fluid.isEmpty() && !listEmptyTanks)) {
                continue;
            }
            Component name = fluid.isEmpty() ? tr("empty") : fluid.getHoverName();
            tankLines.add(tr("tank", tank + 1, fluids.getAmountAsInt(tank), fluids.getCapacityAsInt(tank, fluid), name));
        }
        lines.add(tr("fluids", tanks, yesNo(canDrain)));
        lines.addAll(tankLines);
        if (!listEmptyTanks && filled > tankLines.size()) {
            lines.add(tr("tank.more", filled - tankLines.size()).withStyle(ChatFormatting.GRAY));
        }

        // Every candidate is tried as it is: the amount and components can decide whether a fill is taken.
        List<FluidStack> candidates = new ArrayList<>();
        for (ItemResource item : carried) {
            FluidStack held = FluidUtil.getFirstStackContained(item.toStack());
            if (!held.isEmpty()) {
                candidates.add(held);
            }
        }
        for (Fluid sample : SAMPLE_FLUIDS) {
            candidates.add(new FluidStack(sample, FluidType.BUCKET_VOLUME));
        }
        List<Component> accepted = new ArrayList<>();
        for (FluidStack candidate : candidates) {
            Component name = candidate.getHoverName();
            if (!accepted.contains(name)
                    && simulate(tx -> fluids.insert(FluidResource.of(candidate), candidate.getAmount(), tx)) > 0) {
                accepted.add(name);
            }
        }
        lines.add(tr("accepts", accepted.isEmpty()
                ? tr("accepts.no_fluids").withStyle(ChatFormatting.RED)
                : nameList(accepted).withStyle(ChatFormatting.GREEN)));
    }

    private static MutableComponent nameList(List<Component> names) {
        int shown = Math.min(names.size(), MAX_ACCEPTED_NAMES);
        Component list = ComponentUtils.formatList(names.subList(0, shown), Component.literal(", "));
        return names.size() > shown ? tr("accepts.more", list, names.size() - shown) : list.copy();
    }

    private static Component summary(Level level, BlockPos pos, @Nullable Direction side) {
        List<Component> exposed = new ArrayList<>();
        if (level.getCapability(Capabilities.Item.BLOCK, pos, side) != null) {
            exposed.add(tr("label.items"));
        }
        if (level.getCapability(Capabilities.Fluid.BLOCK, pos, side) != null) {
            exposed.add(tr("label.fluids"));
        }
        if (level.getCapability(Capabilities.Energy.BLOCK, pos, side) != null) {
            exposed.add(tr("label.energy"));
        }
        exposed.addAll(otherCapabilities(level, pos, side, side == null));
        Component what = exposed.isEmpty()
                ? tr("nothing").withStyle(ChatFormatting.GRAY)
                : ComponentUtils.formatList(exposed, Component.literal(", "));
        return tr("summary", sideName(side), what);
    }

    private static Component header(Component block, Component side) {
        return tr("header", block, side).withStyle(ChatFormatting.GOLD);
    }

    private static Component sideName(@Nullable Direction side) {
        return side == null ? tr("unsided") : tr("side." + side.getName());
    }

    private static Component yesNo(boolean value) {
        return value ? tr("yes").withStyle(ChatFormatting.GREEN) : tr("no").withStyle(ChatFormatting.RED);
    }

    private static MutableComponent tr(String key, Object... args) {
        return Component.translatable("portprobe." + key, args);
    }
}

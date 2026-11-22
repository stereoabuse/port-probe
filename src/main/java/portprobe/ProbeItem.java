package portprobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

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
import net.minecraft.world.item.ItemStackLinkedSet;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

public class ProbeItem extends Item {
    private static final int MAX_SLOT_LINES = 6;
    private static final int MAX_ACCEPTED_NAMES = 6;
    // Larger inventories are reported by slot count only; scanning them could stall the server tick.
    private static final int MAX_TESTED_SLOTS = 4096;
    private static final int HOLD_GUARD_TICKS = 8;
    // Arbitrary items used to tell "accepts anything" from "filtered" without relying on the player's inventory.
    private static final List<Item> SAMPLE_ITEMS = List.of(Items.COBBLESTONE, Items.STICK, Items.FEATHER);
    private static final List<Fluid> SAMPLE_FLUIDS = List.of(Fluids.WATER, Fluids.LAVA);

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
        if (!level.isClientSide) {
            // Restarted on every attempt, so holding the button reads once instead of repeating.
            boolean held = player.getCooldowns().isOnCooldown(this);
            player.getCooldowns().addCooldown(this, HOLD_GUARD_TICKS);
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
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(tr("tooltip.use").withStyle(ChatFormatting.GRAY));
        tooltip.add(tr("tooltip.sneak").withStyle(ChatFormatting.GRAY));
        tooltip.add(tr("tooltip.test").withStyle(ChatFormatting.GRAY));
    }

    /** One of each distinct item (components included) the player carries, hotbar first, without the probe itself. */
    private static List<ItemStack> carriedItems(Player player) {
        Inventory inventory = player.getInventory();
        int hotbar = Inventory.getSelectionSize();
        List<ItemStack> ordered = new ArrayList<>(inventory.items.subList(0, hotbar));
        ordered.addAll(inventory.offhand);
        ordered.addAll(inventory.items.subList(hotbar, inventory.items.size()));

        Set<ItemStack> distinct = ItemStackLinkedSet.createTypeAndComponentsSet();
        for (ItemStack stack : ordered) {
            if (!stack.isEmpty() && !(stack.getItem() instanceof ProbeItem)) {
                distinct.add(stack.copyWithCount(1));
            }
        }
        return List.copyOf(distinct);
    }

    /**
     * Why the item contents must be left alone, or null. Reading an unopened loot container would
     * roll its loot, and a locked one is not this player's to look into.
     */
    @Nullable
    private static Component notReadReason(Level level, BlockPos pos, Player player) {
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
        List<ItemStack> carried = carriedItems(player);

        IItemHandler items = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
        Component notRead = items == null ? null : notReadReason(level, pos, player);
        if (items == null) {
            lines.add(tr("items.none").withStyle(ChatFormatting.GRAY));
        } else if (notRead != null) {
            lines.add(notRead);
        } else {
            addItemLines(lines, items, carried);
        }

        IFluidHandler fluids = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
        if (fluids == null) {
            lines.add(tr("fluids.none").withStyle(ChatFormatting.GRAY));
        } else {
            addFluidLines(lines, fluids, carried);
        }

        IEnergyStorage energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
        if (energy == null) {
            lines.add(tr("energy.none").withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(tr("energy", energy.getEnergyStored(), energy.getMaxEnergyStored(),
                    yesNo(energy.canReceive()), yesNo(energy.canExtract())));
        }
        return lines;
    }

    private static void addItemLines(List<Component> lines, IItemHandler items, List<ItemStack> carried) {
        int slots = items.getSlots();
        if (slots > MAX_TESTED_SLOTS) {
            lines.add(tr("items.too_large", slots).withStyle(ChatFormatting.GRAY));
            return;
        }
        int occupied = 0;
        boolean canExtract = false;
        for (int slot = 0; slot < slots; slot++) {
            if (!items.getStackInSlot(slot).isEmpty()) {
                occupied++;
            }
            if (!canExtract && !items.extractItem(slot, 1, true).isEmpty()) {
                canExtract = true;
            }
        }
        lines.add(tr("items", slots, occupied, yesNo(canExtract)));

        List<ItemStack> samples = SAMPLE_ITEMS.stream().map(ItemStack::new).toList();

        // Small inventories list every slot; large ones only the occupied slots, capped.
        boolean listEmptySlots = slots <= MAX_SLOT_LINES;
        int listed = 0;
        for (int slot = 0; slot < slots && listed < MAX_SLOT_LINES; slot++) {
            ItemStack content = items.getStackInSlot(slot);
            if (content.isEmpty() && !listEmptySlots) {
                continue;
            }
            Component what = content.isEmpty() ? tr("empty") : tr("stack", content.getHoverName(), content.getCount());
            lines.add(tr("slot", slot + 1, what, slotRole(items, slot, content, carried, samples)));
            listed++;
        }
        if (!listEmptySlots && occupied > listed) {
            lines.add(tr("slot.more", occupied - listed).withStyle(ChatFormatting.GRAY));
        }

        lines.add(tr("accepts", acceptedItems(items, carried, samples, slots > 0 && occupied == slots)));
    }

    private static Component slotRole(IItemHandler items, int slot, ItemStack content,
            List<ItemStack> carried, List<ItemStack> samples) {
        List<ItemStack> candidates = new ArrayList<>();
        if (!content.isEmpty()) {
            candidates.add(content.copyWithCount(1));
        }
        candidates.addAll(carried);
        candidates.addAll(samples);

        boolean in = false;
        for (ItemStack candidate : candidates) {
            if (items.insertItem(slot, candidate, true).isEmpty()) {
                in = true;
                break;
            }
        }
        boolean out = !items.extractItem(slot, 1, true).isEmpty();

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

    private static Component acceptedItems(IItemHandler items, List<ItemStack> carried, List<ItemStack> samples,
            boolean full) {
        List<Component> accepted = new ArrayList<>();
        boolean allCarried = true;
        for (ItemStack stack : carried) {
            if (!ItemHandlerHelper.insertItem(items, stack, true).isEmpty()) {
                allCarried = false;
            } else if (!accepted.contains(stack.getHoverName())) {
                accepted.add(stack.getHoverName());
            }
        }
        long samplesAccepted = samples.stream()
                .filter(sample -> ItemHandlerHelper.insertItem(items, sample, true).isEmpty())
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

    private static void addFluidLines(List<Component> lines, IFluidHandler fluids, List<ItemStack> carried) {
        // Some handlers only drain in whole increments (a cauldron gives nothing for 1 mB).
        boolean canDrain = !fluids.drain(Integer.MAX_VALUE, FluidAction.SIMULATE).isEmpty();
        int tanks = fluids.getTanks();
        lines.add(tr("fluids", tanks, yesNo(canDrain)));

        // Same rule as item slots: few tanks list all of them, many only the filled ones, capped.
        boolean listEmptyTanks = tanks <= MAX_SLOT_LINES;
        int filled = 0;
        int listed = 0;
        for (int tank = 0; tank < tanks; tank++) {
            FluidStack fluid = fluids.getFluidInTank(tank);
            if (!fluid.isEmpty()) {
                filled++;
            }
            if (listed == MAX_SLOT_LINES || (fluid.isEmpty() && !listEmptyTanks)) {
                continue;
            }
            Component name = fluid.isEmpty() ? tr("empty") : fluid.getHoverName();
            lines.add(tr("tank", tank + 1, fluid.getAmount(), fluids.getTankCapacity(tank), name));
            listed++;
        }
        if (!listEmptyTanks && filled > listed) {
            lines.add(tr("tank.more", filled - listed).withStyle(ChatFormatting.GRAY));
        }

        // Every candidate is tried as it is: the amount and components can decide whether a fill is taken.
        List<FluidStack> candidates = new ArrayList<>();
        for (ItemStack stack : carried) {
            FluidUtil.getFluidContained(stack).ifPresent(candidates::add);
        }
        for (Fluid sample : SAMPLE_FLUIDS) {
            candidates.add(new FluidStack(sample, 1000));
        }
        List<Component> accepted = new ArrayList<>();
        for (FluidStack candidate : candidates) {
            Component name = candidate.getHoverName();
            if (!accepted.contains(name) && fluids.fill(candidate.copy(), FluidAction.SIMULATE) > 0) {
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
        if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side) != null) {
            exposed.add(tr("label.items"));
        }
        if (level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side) != null) {
            exposed.add(tr("label.fluids"));
        }
        if (level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side) != null) {
            exposed.add(tr("label.energy"));
        }
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

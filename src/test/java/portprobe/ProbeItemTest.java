package portprobe;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeItemTest {
    private static TranslatableContents translation(Component line) {
        return (TranslatableContents) line.getContents();
    }

    private static TranslatableContents argument(Component line, int index) {
        return translation((Component) translation(line).getArgs()[index]);
    }

    @Test
    void recognizesBothNoContextRegistrations() {
        var id = ResourceLocation.fromNamespaceAndPath("portprobe", "test_void");
        assertTrue(ProbeItem.isContextFree(BlockCapability.createVoid(id, Object.class)));
        assertTrue(ProbeItem.isContextFree(BlockCapability.create(id.withSuffix("_boxed"), Object.class, Void.class)));
        assertFalse(ProbeItem.isContextFree(Capabilities.ItemHandler.BLOCK));
    }

    @Test
    void holdGuardTracksBlockDimensionFaceModeAndTime() {
        var block = GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO);
        var previous = new ProbeItem.ProbeUse(block, Direction.UP, false, 10);
        assertFalse(previous.repeats(null));
        assertTrue(new ProbeItem.ProbeUse(block, Direction.UP, false, 409).repeats(previous));
        assertFalse(new ProbeItem.ProbeUse(block, Direction.UP, false, 410).repeats(previous));
        assertFalse(new ProbeItem.ProbeUse(block, Direction.DOWN, false, 11).repeats(previous));
        assertFalse(new ProbeItem.ProbeUse(block, Direction.UP, true, 11).repeats(previous));
        var above = GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO.above());
        var nether = GlobalPos.of(Level.NETHER, BlockPos.ZERO);
        assertFalse(new ProbeItem.ProbeUse(above, Direction.UP, false, 11).repeats(previous));
        assertFalse(new ProbeItem.ProbeUse(nether, Direction.UP, false, 11).repeats(previous));
        var allSides = new ProbeItem.ProbeUse(block, Direction.UP, true, 10);
        assertTrue(new ProbeItem.ProbeUse(block, Direction.DOWN, true, 11).repeats(allSides));
        assertTrue(new ProbeItem.ProbeUse(above, Direction.UP, false, 159).tooSoonAfter(previous));
        assertFalse(new ProbeItem.ProbeUse(above, Direction.UP, false, 160).tooSoonAfter(previous));
    }

    @Test
    void finishingOnTheLastAllowedInsertionIsACompleteScan() {
        var items = new ItemStackHandler(1024) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return false;
            }
        };
        List<Component> lines = new ArrayList<>();
        ProbeItem.addItemLines(lines, items, List.of(new ItemStack(Items.DIRT)));
        assertEquals("portprobe.accepts.none", argument(lines.getLast(), 0).getKey());
    }

    @Test
    void occupiedSlotCanStillTakeItsContents() {
        var items = new ItemStackHandler(1);
        items.setStackInSlot(0, new ItemStack(Items.IRON_INGOT));
        List<Component> lines = new ArrayList<>();
        ProbeItem.addItemLines(lines, items, List.of(new ItemStack(Items.DIRT)));
        assertEquals("portprobe.role.both", argument(lines.get(1), 2).getKey());
        assertEquals("portprobe.accepts.full", argument(lines.getLast(), 0).getKey());
        assertEquals(1, items.getStackInSlot(0).getCount());
    }

    @Test
    void slotRolesAndAcceptanceShareOneBudgetAndKeepConfirmedMatches() {
        class CountingHandler extends ItemStackHandler {
            int attempts;

            CountingHandler() {
                super(4096);
                setStackInSlot(0, new ItemStack(Items.DIAMOND));
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                assertTrue(simulate);
                attempts++;
                return slot == 0 && stack.is(Items.DIAMOND) ? ItemStack.EMPTY : stack;
            }
        }
        var items = new CountingHandler();
        List<Component> lines = new ArrayList<>();
        ProbeItem.addItemLines(lines, items, List.of(new ItemStack(Items.DIAMOND), new ItemStack(Items.DIRT)));
        assertEquals(4096, items.attempts);
        var accepts = argument(lines.getLast(), 0);
        assertEquals("portprobe.accepts.partial", accepts.getKey());
        assertEquals(Items.DIAMOND.getDescription().getString(), ((Component) accepts.getArgs()[0]).getString());
    }

    @Test
    void oversizedFluidHandlerOnlyReportsItsCount() {
        var tanks = new FluidTank(1000) {
            @Override public int getTanks() { return 257; }
            @Override public FluidStack getFluidInTank(int tank) { throw new AssertionError("read tank"); }
            @Override public FluidStack drain(int amount, FluidAction action) { throw new AssertionError("drained"); }
            @Override public int fill(FluidStack resource, FluidAction action) { throw new AssertionError("filled"); }
        };
        List<Component> lines = new ArrayList<>();
        ProbeItem.addFluidLines(lines, tanks, List.of());
        assertEquals(1, lines.size());
        assertEquals("portprobe.fluids.too_large", translation(lines.getFirst()).getKey());
        assertEquals(257, translation(lines.getFirst()).getArgs()[0]);
    }

    @EventBusSubscriber(modid = PortProbe.MODID)
    public static class TestContainers {
        @SubscribeEvent
        public static void register(RegisterCapabilitiesEvent event) {
            event.registerItem(Capabilities.FluidHandler.ITEM, (stack, ctx) -> new CarriedTanks(stack), Items.BRICK);
        }
    }

    private static class CarriedTanks extends FluidTank implements IFluidHandlerItem {
        private final ItemStack container;

        CarriedTanks(ItemStack container) {
            super(1000);
            this.container = container;
        }

        @Override public ItemStack getContainer() { return container; }
        @Override public int getTanks() { return container.has(DataComponents.CUSTOM_NAME) ? 256 : 2; }

        @Override
        public FluidStack getFluidInTank(int tank) {
            if (tank == 1) {
                var fluid = new FluidStack(Fluids.LAVA, 375);
                fluid.set(DataComponents.CUSTOM_NAME, Component.literal("Tank two"));
                return fluid;
            }
            return FluidStack.EMPTY;
        }
    }

    @Test
    void testsSecondTankWithItsExactAmountAndComponents() {
        var target = new FluidTank(1000, fluid -> fluid.is(Fluids.LAVA) && fluid.getAmount() == 375
                && Component.literal("Tank two").equals(fluid.get(DataComponents.CUSTOM_NAME)));
        List<Component> lines = new ArrayList<>();
        ProbeItem.addFluidLines(lines, target, List.of(new ItemStack(Items.BRICK)));
        var expectedName = new FluidStack(Fluids.LAVA, 375).getHoverName().getString();
        assertEquals(expectedName, ((Component) translation(lines.getLast()).getArgs()[0]).getString());
        assertTrue(target.isEmpty());
    }

    @Test
    void carriedTankLimitIsSharedAndMarksIncompleteResults() {
        var first = new ItemStack(Items.BRICK);
        first.set(DataComponents.CUSTOM_NAME, Component.literal("Large container"));
        var target = new FluidTank(1000, fluid -> false);
        List<Component> lines = new ArrayList<>();
        ProbeItem.addFluidLines(lines, target, List.of(first));
        assertEquals("portprobe.accepts.no_fluids", argument(lines.getLast(), 0).getKey());
        lines.clear();
        ProbeItem.addFluidLines(lines, target, List.of(first, new ItemStack(Items.BRICK)));
        assertEquals("portprobe.accepts.incomplete", argument(lines.getLast(), 0).getKey());
    }

    @Test
    @ExtendWith(EphemeralTestServerProvider.class)
    void anyCraftingIngredientUnlocksTheLoadedRecipe(MinecraftServer server) {
        var recipe = ResourceLocation.fromNamespaceAndPath("portprobe", "port_probe");
        var advancement = server.getAdvancements().get(recipe.withPrefix("recipes/tools/")).value();
        assertTrue(server.getRecipeManager().byKey(recipe).isPresent());
        assertEquals(List.of(recipe), advancement.rewards().recipes());
        var trigger = (InventoryChangeTrigger.TriggerInstance) advancement.criteria().get("has_ingredient").triggerInstance();
        var ingredient = trigger.items().getFirst();
        for (var item : List.of(Items.COMPARATOR, Items.IRON_INGOT, Items.REDSTONE)) {
            assertTrue(ingredient.test(new ItemStack(item)));
        }
        assertFalse(ingredient.test(new ItemStack(Items.DIRT)));
    }
}

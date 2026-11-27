package portprobe;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.advancements.criterion.InventoryChangeTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
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
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EphemeralTestServerProvider.class)
class ProbeItemTest {
    // Item stacks cannot be made until a server has loaded and bound the items' components.
    @BeforeAll
    static void startServer(MinecraftServer server) {}

    private static TranslatableContents translation(Component line) {
        return (TranslatableContents) line.getContents();
    }

    private static TranslatableContents argument(Component line, int index) {
        return translation((Component) translation(line).getArgs()[index]);
    }

    @Test
    void recognizesBothNoContextRegistrations() {
        var id = Identifier.fromNamespaceAndPath("portprobe", "test_void");
        assertTrue(ProbeItem.isContextFree(BlockCapability.createVoid(id, Object.class)));
        assertTrue(ProbeItem.isContextFree(BlockCapability.create(id.withSuffix("_boxed"), Object.class, Void.class)));
        assertFalse(ProbeItem.isContextFree(Capabilities.Item.BLOCK));
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
        var items = new ItemStacksResourceHandler(1024) {
            @Override
            public boolean isValid(int index, ItemResource resource) {
                return false;
            }
        };
        List<Component> lines = new ArrayList<>();
        ProbeItem.addItemLines(lines, items, List.of(ItemResource.of(Items.DIRT)));
        assertEquals("portprobe.accepts.none", argument(lines.getLast(), 0).getKey());
    }

    @Test
    void occupiedSlotCanStillTakeItsContents() {
        var items = new ItemStacksResourceHandler(1);
        items.set(0, ItemResource.of(Items.IRON_INGOT), 1);
        List<Component> lines = new ArrayList<>();
        ProbeItem.addItemLines(lines, items, List.of(ItemResource.of(Items.DIRT)));
        assertEquals("portprobe.role.both", argument(lines.get(1), 2).getKey());
        assertEquals("portprobe.accepts.full", argument(lines.getLast(), 0).getKey());
        assertEquals(1, items.getAmountAsInt(0));
    }

    @Test
    void slotRolesAndAcceptanceShareOneBudgetAndKeepConfirmedMatches() {
        class CountingHandler extends ItemStacksResourceHandler {
            int attempts;

            CountingHandler() {
                super(4096);
                set(0, ItemResource.of(Items.DIAMOND), 1);
            }

            @Override
            public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
                attempts++;
                return index == 0 && resource.is(Items.DIAMOND) ? amount : 0;
            }
        }
        var items = new CountingHandler();
        List<Component> lines = new ArrayList<>();
        ProbeItem.addItemLines(lines, items, List.of(ItemResource.of(Items.DIAMOND), ItemResource.of(Items.DIRT)));
        assertEquals(4096, items.attempts);
        var accepts = argument(lines.getLast(), 0);
        assertEquals("portprobe.accepts.partial", accepts.getKey());
        assertEquals(ItemResource.of(Items.DIAMOND).getHoverName().getString(),
                ((Component) accepts.getArgs()[0]).getString());
    }

    @Test
    void oversizedFluidHandlerOnlyReportsItsCount() {
        ResourceHandler<FluidResource> tanks = new ResourceHandler<>() {
            @Override public int size() { return 257; }
            @Override public FluidResource getResource(int index) { throw new AssertionError("read tank"); }
            @Override public long getAmountAsLong(int index) { throw new AssertionError("read tank"); }
            @Override public long getCapacityAsLong(int index, FluidResource resource) { throw new AssertionError("read tank"); }
            @Override public boolean isValid(int index, FluidResource resource) { throw new AssertionError("read tank"); }

            @Override
            public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
                throw new AssertionError("filled");
            }

            @Override
            public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
                throw new AssertionError("drained");
            }
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
            event.registerItem(Capabilities.Fluid.ITEM, (stack, access) -> new CarriedTanks(stack), Items.BRICK);
        }
    }

    private static class CarriedTanks implements ResourceHandler<FluidResource> {
        private final ItemStack container;

        CarriedTanks(ItemStack container) {
            this.container = container;
        }

        @Override public int size() { return container.has(DataComponents.CUSTOM_NAME) ? 256 : 2; }

        @Override
        public FluidResource getResource(int index) {
            return index == 1
                    ? FluidResource.of(Fluids.LAVA).with(DataComponents.CUSTOM_NAME, Component.literal("Tank two"))
                    : FluidResource.EMPTY;
        }

        @Override public long getAmountAsLong(int index) { return index == 1 ? 375 : 0; }
        @Override public long getCapacityAsLong(int index, FluidResource resource) { return 1000; }
        @Override public boolean isValid(int index, FluidResource resource) { return true; }

        @Override
        public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
            return 0;
        }
    }

    @Test
    void testsSecondTankWithItsExactAmountAndComponents() {
        var target = new FluidStacksResourceHandler(1, 1000) {
            @Override
            public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
                boolean wanted = resource.getFluid() == Fluids.LAVA && amount == 375
                        && Component.literal("Tank two").equals(resource.getComponents().get(DataComponents.CUSTOM_NAME));
                return wanted ? super.insert(index, resource, amount, transaction) : 0;
            }
        };
        List<Component> lines = new ArrayList<>();
        ProbeItem.addFluidLines(lines, target, List.of(ItemResource.of(Items.BRICK)));
        var expectedName = new FluidStack(Fluids.LAVA, 375).getHoverName().getString();
        assertEquals(expectedName, ((Component) translation(lines.getLast()).getArgs()[0]).getString());
        assertEquals(0, target.getAmountAsInt(0));
    }

    @Test
    void carriedTankLimitIsSharedAndMarksIncompleteResults() {
        var first = new ItemStack(Items.BRICK);
        first.set(DataComponents.CUSTOM_NAME, Component.literal("Large container"));
        var target = new FluidStacksResourceHandler(1, 1000) {
            @Override
            public boolean isValid(int index, FluidResource resource) {
                return false;
            }
        };
        List<Component> lines = new ArrayList<>();
        ProbeItem.addFluidLines(lines, target, List.of(ItemResource.of(first)));
        assertEquals("portprobe.accepts.no_fluids", argument(lines.getLast(), 0).getKey());
        lines.clear();
        ProbeItem.addFluidLines(lines, target, List.of(ItemResource.of(first), ItemResource.of(Items.BRICK)));
        assertEquals("portprobe.accepts.incomplete", argument(lines.getLast(), 0).getKey());
    }

    @Test
    void anyCraftingIngredientUnlocksTheLoadedRecipe(MinecraftServer server) {
        var id = Identifier.fromNamespaceAndPath("portprobe", "port_probe");
        var recipe = ResourceKey.create(Registries.RECIPE, id);
        var advancement = server.getAdvancements().get(id.withPrefix("recipes/tools/")).value();
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

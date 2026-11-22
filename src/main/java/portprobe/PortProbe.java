package portprobe;

import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(PortProbe.MODID)
public class PortProbe {
    public static final String MODID = "portprobe";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredItem<ProbeItem> PROBE =
            ITEMS.registerItem("port_probe", ProbeItem::new, new Item.Properties().stacksTo(1));

    public PortProbe(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(PortProbe::addToCreativeTab);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, PortProbe::probeWithoutBuildRights);
    }

    // ItemStack skips onItemUseFirst for players who may not build (adventure mode), so the block's
    // GUI would open instead. This runs the probe for them, last, so other mods can still deny the use.
    private static void probeWithoutBuildRights(UseItemOnBlockEvent event) {
        Player player = event.getPlayer();
        ItemStack stack = event.getItemStack();
        if (event.getUsePhase() == UseItemOnBlockEvent.UsePhase.ITEM_BEFORE_BLOCK && player != null
                && !player.getAbilities().mayBuild && stack.getItem() instanceof ProbeItem probe) {
            probe.onItemUseFirst(stack, event.getUseOnContext());
            event.cancelWithResult(ItemInteractionResult.sidedSuccess(event.getLevel().isClientSide));
        }
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(PROBE);
        }
    }
}

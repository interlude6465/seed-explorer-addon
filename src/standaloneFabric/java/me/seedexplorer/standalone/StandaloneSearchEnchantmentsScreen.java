package me.seedexplorer.standalone;

import java.util.*;
import java.util.function.Consumer;
import me.seedexplorer.addon.loot.LootSearchTerm;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Only offers enchantments supported by the selected chest-loot item and dimensions. */
final class StandaloneSearchEnchantmentsScreen extends Screen {
    private final Screen parent;
    private final PredictionContext context;
    private final LootSearchTerm base;
    private final boolean allDimensions;
    private final Consumer<LootSearchTerm> apply;
    private final Map<String,Integer> selected=new LinkedHashMap<>();
    private final List<LootSearchTerm.Property> options;
    private int page;
    private String message="Choose enchantments; click levels to cycle. Any accepts every level.";
    StandaloneSearchEnchantmentsScreen(Screen parent,PredictionContext context,LootSearchTerm base,boolean allDimensions,Consumer<LootSearchTerm> apply) {
        super(Component.literal("Enchantments: "+base.itemId().replace("minecraft:","").replace('_',' ')));
        this.parent=parent;this.context=context;this.base=base;this.allDimensions=allDimensions;this.apply=apply;
        options=StandaloneSearch.enchantmentOptions(context,base.itemId(),allDimensions);
        for(var property:base.properties())selected.put(property.enchantment(),property.level());
    }
    @Override protected void init() {
        int left=Math.max(10,(width-420)/2),span=Math.min(420,width-20),rows=Math.max(1,(height-100)/24);
        for(int i=0;i<rows && page*rows+i<options.size();i++) {
            var option=options.get(page*rows+i);String id=option.enchantment();
            String conflict=StandaloneSearch.enchantmentConflict(properties(),id);
            var choose=addRenderableWidget(Button.builder(Component.literal(label(id)).withStyle(
                conflict==null?net.minecraft.ChatFormatting.WHITE:net.minecraft.ChatFormatting.RED),b->{
                if(conflict!=null && !selected.containsKey(id)){message=conflict;return;}
                if(selected.containsKey(id))selected.remove(id);else selected.put(id,0);
                rebuildWidgets();
            }).bounds(left,45+i*24,span-90,20).build());
            if(conflict!=null)choose.setTooltip(Tooltip.create(Component.literal(conflict)));
            var levelButton=addRenderableWidget(Button.builder(Component.literal(level(id)),b->{
                int max=me.seedexplorer.addon.worldgen.WorldgenEngine.offlineRegistryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getOptional(net.minecraft.resources.Identifier.parse(id)).orElseThrow().getMaxLevel();
                selected.put(id,(selected.getOrDefault(id,0)+1)%(max+1));rebuildWidgets();
            }).bounds(left+span-84,45+i*24,84,20).build());
            levelButton.active=conflict==null;
        }
        addRenderableWidget(Button.builder(Component.literal("<"),b->{page=Math.max(0,page-1);rebuildWidgets();}).bounds(left,height-30,30,20).build());
        addRenderableWidget(Button.builder(Component.literal(">"),b->{page=Math.min((options.size()-1)/rows,page+1);rebuildWidgets();}).bounds(left+36,height-30,30,20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"),b->onClose()).bounds(left+span-174,height-30,80,20).build());
        addRenderableWidget(Button.builder(Component.literal("Apply"),b->{
            var properties=selected.entrySet().stream().map(e->new LootSearchTerm.Property(e.getKey(),e.getValue())).toList();
            String name=base.itemId().replace("minecraft:","").replace('_',' ');
            String suffix=properties.isEmpty()?"":" ["+properties.stream().map(p->p.enchantment().replace("minecraft:","")+"="+p.level()).collect(java.util.stream.Collectors.joining(", "))+"]";
            var term=LootSearchTerm.withProperties(base.itemId(),name+suffix,properties);
            if(!StandaloneSearch.validLootTerm(context,term,allDimensions)) {message="This combination is incompatible or unavailable in supported chest loot.";return;}
            apply.accept(term);onClose();
        }).bounds(left+span-88,height-30,88,20).build());
    }
    private List<LootSearchTerm.Property> properties(){return selected.entrySet().stream().map(e->new LootSearchTerm.Property(e.getKey(),e.getValue())).toList();}
    private String label(String id) {return (selected.containsKey(id)?"[x] ":"[ ] ")+id.replace("minecraft:","").replace('_',' ');}
    private String level(String id) {return !selected.containsKey(id)?"Off":selected.get(id)==0?"Any level":"Level "+selected.get(id);}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float delta) {
        g.fill(0,0,width,height,0xFF101619);g.centeredText(font,title,width/2,10,0xFFFFFFFF);
        g.text(font,font.plainSubstrByWidth(message,width-20),10,28,0xFFE4CE9A);super.extractRenderState(g,x,y,delta);
    }
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}

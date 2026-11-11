package me.seedexplorer.standalone;

import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import me.seedexplorer.addon.worldgen.PredictionContext;

/** Real controls, background close/reopen, and an independently archived vanilla inventory. */
public final class StandaloneSearchWorkflowGameTest {
    public static void run(ClientGameTestContext test) {
        try(TestSingleplayerContext world=test.worldBuilder().setUseConsistentSettings(true).create()) {
            world.getConnection().waitForChunksRender();test.waitTicks(4);
            var config=(StandaloneSeedExplorerConfig)field(StandaloneAncillaryRuntime.get(),"config");
            var prediction=new PredictionContext(-7046029254386353131L,"26.3",0);
            test.runOnClient(client->{
                config.setSeed(prediction.worldSeed());config.setPredictionVersion("26.3");
                config.setInt("search-mode",3,0,3);config.setInt("search-radius",512,512,8192);
                config.setBoolean("search-all-dimensions",false);
                client.gui.setScreen(new StandaloneSearchScreen(null,config,prediction,12037,15480,result->{}));
                ((EditBox)field(client.gui.screen(),"query")).setValue("minecraft:enchanted_book");
            });
            test.waitTicks(2);
            test.runOnClient(client->{var screen=client.gui.screen();var b=button(screen,"...");
                System.out.println("Search editor before click: "+screen.getClass().getSimpleName()+" button="+b.getX()+","+b.getY()+" active="+b.active+" query="+((EditBox)field(screen,"query")).getValue());});
            test.takeScreenshot("seed-explorer-search-editor-before");
            click(test,"...");
            test.runOnClient(client->System.out.println("Search editor after click: "+client.gui.screen().getClass().getSimpleName()));
            test.runOnClient(client->require(client.gui.screen() instanceof StandaloneSearchEnchantmentsScreen,"enchantment editor did not open"));
            selectEnchantment(test,"mending",1);selectEnchantment(test,"unbreaking",3);
            selectEnchantment(test,"protection",4);showEnchantment(test,"fire protection");
            test.runOnClient(client->{
                var incompatible=button(client.gui.screen(),"[ ] fire protection");
                require(incompatible.getMessage().getStyle().getColor().getValue()==0xFF5555,"incompatible option was not red");
            });
            click(test,"[ ] fire protection");
            test.runOnClient(client->require(!((java.util.Map<?,?>)field(client.gui.screen(),"selected")).containsKey("minecraft:fire_protection"),"incompatible red option could still be selected"));
            showEnchantment(test,"protection");click(test,"[x] protection");
            click(test,"Apply");click(test,"Add item");
            for(int r:new int[]{512,1024,2048,4096,8192})click(test,"Radius: "+r);
            test.runOnClient(client->require(((Number)field(client.gui.screen(),"radius")).intValue()==0,"Infinite radius was not selectable after8192"));
            click(test,"Radius: Infinite");
            test.runOnClient(client->{
                var session=StandaloneSearchRuntime.current();
                require(session.requests.size()==1 && session.requests.getFirst().term().properties().size()==2,
                    "multiple selected enchantments were lost on Apply/Add");
                ((EditBox)field(client.gui.screen(),"query")).setValue("enchanted golden apple");
                ((EditBox)field(client.gui.screen(),"quantity")).setValue("2304");
            });
            click(test,"Add item");click(test,"Search");test.waitTicks(2);
            var active=test.computeOnClient(client->StandaloneSearchRuntime.current().job);
            require(active!=null,"search was not dispatched");
            test.runOnClient(client->{
                @SuppressWarnings("unchecked") var rows=(List<Button>)field(client.gui.screen(),"requestButtons");
                require(!rows.isEmpty() && rows.getFirst().getMessage().getString().contains("Searching"),"visible request row lacked Searching state");
                require(rows.getFirst().getMessage().getStyle().getColor()!=null && rows.getFirst().getMessage().getStyle().getColor().getValue()==0xFF5555,
                    "Searching request row was not red");
            });
            test.takeScreenshot("seed-explorer-search-request-rows");click(test,"Done");test.waitTicks(5);
            test.runOnClient(client->{
                require(!active.cancelled(),"closing the menu cancelled background search");
                client.gui.setScreen(new StandaloneSearchScreen(null,config,prediction,0,0,result->{}));
                require(field(client.gui.screen(),"job")==active && StandaloneSearchRuntime.current().requests.size()==2,
                    "reopening lost active job/requests");
                require(StandaloneSearchRuntime.current().x==12037,"reopening changed search origin");
            });
            click(test,"Cancel search");require(active.cancelled(),"explicit cancel did not cancel background job");
            click(test,"Clear items");
            test.runOnClient(client->{
                ((EditBox)field(client.gui.screen(),"query")).setValue("suspicious stew");
                ((EditBox)field(client.gui.screen(),"quantity")).setValue("2");
            });
            click(test,"Add item");click(test,"Search");
            var fixture=test.computeOnClient(client->StandaloneSearchRuntime.current().job);
            click(test,"Done");
            long deadline=System.nanoTime()+120_000_000_000L;
            test.waitFor(client->{require(System.nanoTime()<deadline,"background inventory fixture exceeded120s: "+fixture.status);return fixture.done;},2400);
            test.runOnClient(client->{
                require(!fixture.cancelled() && !fixture.failed,"background inventory search failed: "+fixture.status);
                require(fixture.results.size()==1 && fixture.results.getFirst().x()==12037 && fixture.results.getFirst().y()==42
                    && fixture.results.getFirst().z()==15480 && fixture.results.getFirst().chest().seed()==8220817990207311449L,
                    "background result differs from independent saved vanilla inventory: "+fixture.status);
                client.gui.setScreen(new StandaloneSearchScreen(null,config,prediction,0,0,result->{}));
            });
            test.waitTicks(2);test.takeScreenshot("seed-explorer-search-background-found");
            test.runOnClient(client->{
                var session=StandaloneSearchRuntime.current();require(session.job==fixture,"completed result was lost on reopen");
                config.setSeed(prediction.worldSeed()+1);StandaloneSearchRuntime.tick(client,config);
                require(StandaloneSearchRuntime.current()==null && fixture.cancelled(),"seed change retained stale background job");
                client.gui.setScreen((Screen)null);
            });
            System.out.println("Search workflow live: PASS (red request rows, ... enchantment levels/multiple properties, close/reopen background job, explicit cancel, independent inventory, seed isolation)");
        }
    }
    private static void selectEnchantment(ClientGameTestContext test,String id,int level) {
        showEnchantment(test,id);
        click(test,"[ ] "+id);
        for(int i=0;i<level;i++) {
            int y=test.computeOnClient(client->button(client.gui.screen(),"[x] "+id).getY());
            clickAt(test,test.computeOnClient(client->{
                var b=client.gui.screen().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(v->v.getY()==y && (v.getMessage().getString().equals("Any level") || v.getMessage().getString().startsWith("Level "))).findFirst().orElseThrow();
                return new int[]{b.getX()+b.getWidth()/2,b.getY()+10};
            }));
        }
    }
    private static void showEnchantment(ClientGameTestContext test,String id) {
        for(int i=0;i<15;i++)click(test,"<");
        for(int attempts=0;attempts<15;attempts++) {
            boolean found=test.computeOnClient(client->client.gui.screen().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .anyMatch(b->b.getMessage().getString().equals("[ ] "+id)||b.getMessage().getString().equals("[x] "+id)));
            if(found)break;click(test,">");
        }
    }
    private static void click(ClientGameTestContext test,String label) {
        clickAt(test,test.computeOnClient(client->{var b=button(client.gui.screen(),label);return new int[]{b.getX()+b.getWidth()/2,b.getY()+10};}));
    }
    private static void clickAt(ClientGameTestContext test,int[] point) {
        double[] raw=test.computeOnClient(client->new double[]{point[0]*client.getWindow().getWidth()/(double)client.getWindow().getGuiScaledWidth(),
            point[1]*client.getWindow().getHeight()/(double)client.getWindow().getGuiScaledHeight()});
        test.getInput().setCursorPos(raw[0],raw[1]);test.waitTick();
        test.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);test.waitTick();
    }
    private static Button button(Screen screen,String text) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
            .filter(b->b.visible && b.active && b.getMessage().getString().equals(text)).findFirst()
            .orElseThrow(()->new AssertionError("Missing button: "+text+" on "+screen.getClass().getSimpleName()));
    }
    private static Object field(Object target,String name) {
        try {var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}

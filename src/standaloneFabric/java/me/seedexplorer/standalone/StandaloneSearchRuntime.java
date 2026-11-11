package me.seedexplorer.standalone;

import java.util.ArrayList;
import java.util.List;
import me.seedexplorer.addon.loot.ItemLootRequest;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.client.Minecraft;

/** Client-thread session state, independent of any screen instance. */
final class StandaloneSearchRuntime {
    static final class Session {
        final PredictionContext context;
        final int x,z;
        final List<ItemLootRequest> requests=new ArrayList<>();
        StandaloneSearch.Job job;
        String query="",quantity="1";
        Session(PredictionContext context,int x,int z) {this.context=context;this.x=x;this.z=z;}
    }
    private static Object connection;
    private static String scope;
    private static long seed;
    private static Session session;
    static void tick(Minecraft client,StandaloneSeedExplorerConfig config) {
        identity(client.getConnection(),config.crackingScope(),config.seed());
    }
    static void identity(Object nextConnection,String nextScope,long nextSeed) {
        if(connection!=nextConnection || !java.util.Objects.equals(scope,nextScope) || seed!=nextSeed) {
            if(session!=null && session.job!=null)session.job.cancel();
            session=null;connection=nextConnection;scope=nextScope;seed=nextSeed;
        }
    }
    static Session attach(Minecraft client,StandaloneSeedExplorerConfig config,PredictionContext context,int x,int z) {
        tick(client,config);
        return attach(context,x,z);
    }
    static Session attach(PredictionContext context,int x,int z) {
        if(session==null)session=new Session(context,x,z);
        return session;
    }
    static Session current() {return session;}
}

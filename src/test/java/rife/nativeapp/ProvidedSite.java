package rife.nativeapp;

import rife.engine.Site;
import rife.ioc.HierarchicalProperties;

// a site that's made with what the app told it
public class ProvidedSite implements SiteProvider {
    public Site site(HierarchicalProperties properties) {
        var data = properties.getValueString(NativeApp.DATA);
        return new Site() {
            public void setup() {
                get("/hello", c -> c.print("provided with " + data));
            }
        };
    }
}

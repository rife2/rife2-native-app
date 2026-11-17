package rife.nativeapp;

import rife.engine.Site;

// the site that META-INF/services/rife.engine.Site names in the tests
public class NamedSite extends Site {
    public void setup() {
        get("/hello", c -> c.print("hello from " + properties().getValueString(NativeApp.DATA, "nowhere")));
    }
}

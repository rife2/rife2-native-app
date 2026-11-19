package workorders.ios;

import rife.engine.Site;
import rife.ioc.HierarchicalProperties;
import rife.nativeapp.NativeApp;
import rife.nativeapp.SiteProvider;
import workorders.Field;

import java.io.File;

/**
 * The technician's site inside the app, with the database in the app's
 * directory and the settings that the app tells it. Without the address of
 * an office, the app syncs with a demo office inside it.
 */
public class AppSite implements SiteProvider {
    public Site site(HierarchicalProperties properties) {
        return Field.site(new File(properties.getValueString(NativeApp.DATA_PROPERTY, "build/embedded")),
            Field.office(properties, null), properties);
    }
}

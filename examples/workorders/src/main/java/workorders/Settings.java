package workorders;

import rife.ioc.HierarchicalProperties;

import java.util.Locale;

// A setting of the office or of the technician's site: a property like
// workorders.syncToken, as the app tells the site, or else as a system
// property or the environment variable of that name, WORKORDERS_SYNC_TOKEN,
// which RIFE2's system properties have both of. One that's set to nothing
// is one that isn't set.
final class Settings {
    private Settings() {
    }

    static String value(HierarchicalProperties properties, String name, String fallback) {
        return properties.getValueString(name, properties.getValueString(variable(name), fallback));
    }

    // a setting that's set to nothing, which RIFE2's getValueString takes for one that isn't set
    static String raw(HierarchicalProperties properties, String name, String fallback) {
        var value = properties.getValue(name);
        if (null == value) {
            value = properties.getValue(variable(name));
        }
        return null == value ? fallback : value.toString();
    }

    private static String variable(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").replace('.', '_').toUpperCase(Locale.ROOT);
    }
}

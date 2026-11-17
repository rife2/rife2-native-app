/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

// the tools that come with the extension, as files that can be run
final class Resources {
    private Resources() {
    }

    static File extract(String name, File directory)
    throws IOException {
        var tools = new File(directory, "tools");
        tools.mkdirs();
        var file = new File(tools, name);
        try (var resource = Resources.class.getResourceAsStream(name)) {
            if (null == resource) {
                throw new IOException("The extension has no " + name);
            }
            Files.copy(resource, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (name.endsWith(".sh") || name.endsWith(".py")) {
            file.setExecutable(true);
        }
        return file;
    }
}

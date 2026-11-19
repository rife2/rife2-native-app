/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.nativeapp.android;

/**
 * The texts that the pages can show of their own, in English unless an app
 * sets them, translated for instance, before the first page is shown.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public final class NativeTexts {
    /** The title of the page that says why a page couldn't be shown. */
    public static String pageFailed = "This page couldn't be shown";
    /** The choice to save a file that a page saves. */
    public static String save = "Save";
    /** The choice to share a file that a page saves. */
    public static String share = "Share";
    /** What's said when a file that a page saves couldn't be. */
    public static String saveFailed = "The file couldn't be saved";

    private NativeTexts() {
    }
}

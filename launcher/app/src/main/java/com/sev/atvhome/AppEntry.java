package com.sev.atvhome;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

/** One launchable app. */
final class AppEntry {
    final String packageName;
    final ComponentName component;
    final boolean leanback;
    final String label;
    final Drawable banner;   // null when the app has no TV banner
    final Drawable icon;
    final int color;         // accent sampled from the banner (or icon)
    final boolean system;    // preinstalled: can be disabled but not uninstalled

    AppEntry(String packageName, ComponentName component, boolean leanback, String label,
             Drawable banner, Drawable icon, int color, boolean system) {
        this.packageName = packageName;
        this.component = component;
        this.leanback = leanback;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.color = color;
        this.system = system;
    }
}

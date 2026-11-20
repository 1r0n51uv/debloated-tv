package com.sev.atvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads launchable apps, their banners and accent colours. Runs off the main thread. */
final class AppLoader {

    static final int FALLBACK_COLOR = 0xFF5B6CFF;

    static final class Result {
        final List<AppEntry> dock = new ArrayList<>();
        final List<AppEntry> grid = new ArrayList<>();
    }

    static File dockFile(Context c) {
        return new File(c.getExternalFilesDir(null), "dock.txt");
    }

    static File wallpaperFile(Context c) {
        return new File(c.getExternalFilesDir(null), "wallpaper.jpg");
    }

    static Result load(Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, AppEntry> byPackage = new LinkedHashMap<>();

        // TV apps first, so an app with both kinds of entry uses its leanback activity
        addAll(pm, byPackage, Intent.CATEGORY_LEANBACK_LAUNCHER, true, context.getPackageName());
        addAll(pm, byPackage, Intent.CATEGORY_LAUNCHER, false, context.getPackageName());

        Result result = new Result();
        List<String> dockPackages = readDockPackages(context);
        for (String p : dockPackages) {
            AppEntry e = byPackage.remove(p);
            if (e != null && result.dock.size() < 4) result.dock.add(e);
        }
        result.grid.addAll(byPackage.values());
        Collator collator = Collator.getInstance(Locale.ITALIAN);
        Collections.sort(result.grid, (a, b) -> collator.compare(a.label, b.label));
        return result;
    }

    private static void addAll(PackageManager pm, Map<String, AppEntry> out, String category,
                               boolean leanback, String self) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || self.equals(ai.packageName) || out.containsKey(ai.packageName)) continue;
            // Phone-style entries from system packages are vendor stubs (disclaimer, webview,
            // a Netflix-key helper, Settings) — the TV-facing ones all have leanback entries
            if (!leanback && (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            // Suspended apps (see restore.sh) can't be opened; don't show them
            if ((ai.applicationInfo.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) continue;
            String label = String.valueOf(ri.loadLabel(pm)).trim();
            Drawable banner = ai.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            int color = sampleColor(banner != null ? banner : icon);
            out.put(ai.packageName, new AppEntry(ai.packageName,
                    new ComponentName(ai.packageName, ai.name), leanback, label, banner, icon, color,
                    (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0));
        }
    }

    private static List<String> readDockPackages(Context context) {
        File f = dockFile(context);
        if (f.isFile()) {
            List<String> list = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) list.add(line);
                }
            } catch (Exception ignored) {
            }
            return list;
        }
        return Arrays.asList(context.getResources().getStringArray(R.array.dock_packages));
    }

    /**
     * Picks the banner's dominant vivid hue: a circular mean of hues weighted by saturation
     * and brightness, so a white banner with a red logo still yields red.
     */
    static int sampleColor(Drawable d) {
        if (d == null) return FALLBACK_COLOR;
        final int w = 32, h = 18;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        android.graphics.Rect old = new android.graphics.Rect(d.getBounds());
        d.setBounds(0, 0, w, h);
        d.draw(c);
        d.setBounds(old);

        float[] hsv = new float[3];
        double x = 0, y = 0, weight = 0, sat = 0;
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int p = bmp.getPixel(i, j);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double wt = hsv[1] * hsv[1] * hsv[2];
                double rad = Math.toRadians(hsv[0]);
                x += Math.cos(rad) * wt;
                y += Math.sin(rad) * wt;
                sat += hsv[1] * wt;
                weight += wt;
            }
        }
        bmp.recycle();
        if (weight < 0.6) return FALLBACK_COLOR;  // greyscale banner
        float hue = (float) Math.toDegrees(Math.atan2(y, x));
        if (hue < 0) hue += 360f;
        float s = (float) Math.max(0.55, Math.min(0.9, sat / weight));
        return Color.HSVToColor(new float[]{hue, s, 0.95f});
    }
}

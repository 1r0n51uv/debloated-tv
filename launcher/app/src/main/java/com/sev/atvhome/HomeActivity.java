package com.sev.atvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen. Every D-pad key is handled here against an explicit focus
 * model (top bar / dock / grid / popup menu), so navigation never depends on Android's
 * geometric focus search: Up/Down move exactly one row and row ends don't fall through.
 */
public class HomeActivity extends Activity {

    private static final int ZONE_TOP = 0, ZONE_DOCK = 1, ZONE_GRID = 2, ZONE_MENU = 3, ZONE_SRC = 4;
    private static final int COLUMNS = 4;
    private static final int PILL_INPUTS = 0, PILL_WIFI = 1, PILL_SETTINGS = 2;
    private static final float TILE_SCALE = 1.1f, MOVE_SCALE = 1.18f;
    private static final int DOCK_MAX = 4;
    private static final long LONG_PRESS_MS = 550;
    /** HDMI quick-access cards on the right: label, HDMI port number, image in assets/sources. */
    private static final String[][] SOURCES = {
            {"Switch", "1", "switch.webp"}, {"PS5", "2", "ps5.jpg"}, {"PC", "3", "pc.jpg"}};
    private static final ColorMatrixColorFilter GRAYSCALE = grayscale();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private FrameLayout content;
    private GlassHighlight highlight;
    private GlowView glow;
    private TextView heroTitle, heroHint, greeting, clock, date;
    private LinearLayout dockView, popup;
    private RecyclerView grid;
    private TileAdapter adapter;
    private ImageView wallpaper;
    private View wallpaperDim;
    private final View[] pills = new View[3];
    private TextView wifiText;
    private LinearLayout sourcesView;
    private final View[] srcTiles = new View[SOURCES.length];
    private TvInputManager tvInputs;
    private TvInputManager.TvInputCallback inputCallback;

    private Typeface fontThin, fontLight, fontRegular;
    private SharedPreferences prefs;

    private List<AppEntry> dockApps = new ArrayList<>();
    private List<AppEntry> gridApps = new ArrayList<>();
    private final List<MenuItem> menuItems = new ArrayList<>();
    private int menuReturnZone = ZONE_TOP, menuHeaderCount;
    private boolean menuClosesOnUp, moving, okDown, okLongHandled;

    private int zone = ZONE_DOCK, topIdx = PILL_INPUTS, dockIdx, gridIdx, menuIdx, srcIdx;
    private String savedGridPackage;
    private View focusedTile;
    private boolean resumed, appsLoaded;
    private long dockFileStamp = -1, wallpaperStamp = -1;
    private int screenH;
    private float dp;

    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("home", MODE_PRIVATE);

        fontThin = font(200);
        fontLight = font(300);
        fontRegular = font(400);

        content = findViewById(R.id.content);
        highlight = findViewById(R.id.highlight);
        glow = findViewById(R.id.glow);
        heroTitle = findViewById(R.id.hero_title);
        heroHint = findViewById(R.id.hero_hint);
        greeting = findViewById(R.id.greeting);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        dockView = findViewById(R.id.dock);
        popup = findViewById(R.id.popup);
        grid = findViewById(R.id.grid);
        wallpaper = findViewById(R.id.wallpaper);
        wallpaperDim = findViewById(R.id.wallpaper_dim);

        screenH = getWindowManager().getCurrentWindowMetrics().getBounds().height();
        View hero = findViewById(R.id.hero);
        ViewGroup.LayoutParams lp = hero.getLayoutParams();
        lp.height = screenH;
        hero.setLayoutParams(lp);

        heroTitle.setTypeface(fontLight);
        heroHint.setTypeface(fontRegular);
        greeting.setTypeface(fontRegular);
        clock.setTypeface(fontThin);
        date.setTypeface(fontRegular);
        ((TextView) findViewById(R.id.grid_label)).setTypeface(fontRegular);

        setupPill(PILL_INPUTS, R.id.pill_inputs, R.drawable.ic_input, getString(R.string.inputs));
        setupPill(PILL_WIFI, R.id.pill_wifi, R.drawable.ic_wifi, getString(R.string.wifi));
        setupPill(PILL_SETTINGS, R.id.pill_settings, R.drawable.ic_settings, getString(R.string.settings));
        wifiText = pills[PILL_WIFI].findViewById(R.id.pill_text);

        setupSources();

        adapter = new TileAdapter(fontRegular);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setItemAnimator(null);
        grid.setAdapter(adapter);
        grid.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect out, @NonNull View v, @NonNull RecyclerView p,
                                       @NonNull RecyclerView.State s) {
                out.bottom = Math.round(16 * dp);
            }
        });

        restoreFocusState();
        updateClock();
        reloadApps();

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packageReceiver, pkgFilter);

        connectivity = getSystemService(ConnectivityManager.class);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockReceiver, f);
        updateClock();

        // Event-driven network name updates; no polling
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) {
                main.post(HomeActivity.this::updateNetworkPill);
            }

            @Override
            public void onLost(@NonNull Network n) {
                main.post(HomeActivity.this::updateNetworkPill);
            }
        };
        connectivity.registerDefaultNetworkCallback(networkCallback);
        updateNetworkPill();

        // Source cards follow the TV input framework's connected state
        inputCallback = new TvInputManager.TvInputCallback() {
            @Override
            public void onInputStateChanged(@NonNull String inputId, int state) {
                main.post(HomeActivity.this::updateSources);
            }
        };
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, main);
        updateSources();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        File dockFile = AppLoader.dockFile(this);
        long stamp = dockFile.isFile() ? dockFile.lastModified() : 0;
        if (dockFileStamp != -1 && stamp != dockFileStamp) reloadApps();
        dockFileStamp = stamp;
        loadWallpaperIfChanged();
        if (appsLoaded) content.post(() -> applyFocus(false));
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (moving) finishMove();
        unregisterReceiver(clockReceiver);
        if (networkCallback != null) connectivity.unregisterNetworkCallback(networkCallback);
        networkCallback = null;
        if (tvInputs != null && inputCallback != null) tvInputs.unregisterCallback(inputCallback);
        inputCallback = null;
        closeMenu();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already on the home screen: back to the dock, scrolled to top
        if (resumed) {
            closeMenu();
            zone = dockApps.isEmpty() ? ZONE_TOP : ZONE_DOCK;
            applyFocus(true);
        }
    }

    // ---------------------------------------------------------------- apps

    private void reloadApps() {
        final Context app = getApplicationContext();
        worker.execute(() -> {
            AppLoader.Result r = AppLoader.load(app);
            main.post(() -> applyApps(r));
        });
    }

    private void applyApps(AppLoader.Result r) {
        if (isDestroyed()) return;
        dockApps = r.dock;
        gridApps = r.grid;
        focusedTile = null;

        dockView.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (AppEntry e : dockApps) {
            View tile = inflater.inflate(R.layout.item_tile, dockView, false);
            TileAdapter.bind(tile, e, fontRegular);
            tile.findViewById(R.id.name).setVisibility(View.GONE);
            dockView.addView(tile);
        }
        dockView.setVisibility(dockApps.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.setApps(gridApps);

        // Re-resolve the remembered grid tile by package, since positions shift on installs
        if (savedGridPackage != null) {
            for (int i = 0; i < gridApps.size(); i++) {
                if (gridApps.get(i).packageName.equals(savedGridPackage)) gridIdx = i;
            }
        }
        dockIdx = clamp(dockIdx, 0, dockApps.size() - 1);
        gridIdx = clamp(gridIdx, 0, gridApps.size() - 1);
        if (zone == ZONE_DOCK && dockApps.isEmpty()) zone = ZONE_TOP;
        if (zone == ZONE_GRID && gridApps.isEmpty()) zone = ZONE_DOCK;
        if (zone == ZONE_MENU) zone = ZONE_TOP;
        if (!dockApps.isEmpty()) {
            AppEntry d = dockApps.get(dockIdx);
            heroTitle.setText(d.label);
            glow.setGlowColor(d.color, false);
        }
        appsLoaded = true;
        content.post(() -> applyFocus(false));
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            reloadApps();
        }
    };

    private void launch(AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(e.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        safeStart(i);
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (e.getAction() == KeyEvent.ACTION_DOWN) move(code);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Short press = open, hold = app menu. The menu opens on the first key repeat
                // (OK still held); remotes that don't auto-repeat get it on release instead.
                // okDown ignores a release whose press started in another app.
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okLongHandled = false;
                    } else if (okDown && !okLongHandled) {
                        okLongHandled = true;
                        longPress();
                    }
                } else if (e.getAction() == KeyEvent.ACTION_UP && okDown) {
                    okDown = false;
                    if (!okLongHandled) {
                        if (e.getEventTime() - e.getDownTime() >= LONG_PRESS_MS) longPress();
                        else select();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (e.getAction() == KeyEvent.ACTION_UP) back();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void move(int code) {
        if (moving) {
            // Move mode: Left/Right carry the app along the dock, nothing else moves focus
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && dockIdx > 0) swapDock(dockIdx, dockIdx - 1);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && dockIdx < dockApps.size() - 1) swapDock(dockIdx, dockIdx + 1);
            return;
        }
        int before = zone * 1000 + index();
        switch (zone) {
            case ZONE_TOP:
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && topIdx > 0) topIdx--;
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && topIdx < pills.length - 1) topIdx++;
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (topIdx == PILL_SETTINGS) { zone = ZONE_SRC; srcIdx = 0; }   // cards sit right below it
                    else if (!dockApps.isEmpty()) zone = ZONE_DOCK;
                    else if (!gridApps.isEmpty()) { zone = ZONE_GRID; gridIdx = 0; }
                }
                break;
            case ZONE_DOCK:
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && dockIdx > 0) dockIdx--;
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (dockIdx < dockApps.size() - 1) dockIdx++;
                    else { zone = ZONE_SRC; srcIdx = SOURCES.length - 1; }
                }
                else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    // The last dock app is the one under the source cards
                    if (dockIdx == dockApps.size() - 1) { zone = ZONE_SRC; srcIdx = SOURCES.length - 1; }
                    else zone = ZONE_TOP;
                }
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN && !gridApps.isEmpty()) {
                    zone = ZONE_GRID;
                    gridIdx = Math.min(dockIdx, gridApps.size() - 1);   // first row, same column
                }
                break;
            case ZONE_SRC:
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (srcIdx > 0) srcIdx--;
                    else { zone = ZONE_TOP; topIdx = PILL_SETTINGS; }
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (srcIdx < SOURCES.length - 1) srcIdx++;
                    else if (!dockApps.isEmpty()) { zone = ZONE_DOCK; dockIdx = dockApps.size() - 1; }
                } else if (code == KeyEvent.KEYCODE_DPAD_LEFT && !dockApps.isEmpty()) {
                    zone = ZONE_DOCK;
                    dockIdx = dockApps.size() - 1;
                }
                break;
            case ZONE_GRID: {
                int col = gridIdx % COLUMNS, row = gridIdx / COLUMNS;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && col > 0) gridIdx--;
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && col < COLUMNS - 1
                        && gridIdx + 1 < gridApps.size()) gridIdx++;
                else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (row > 0) gridIdx -= COLUMNS;
                    else if (!dockApps.isEmpty()) {
                        zone = ZONE_DOCK;
                        dockIdx = Math.min(col, dockApps.size() - 1);
                    } else zone = ZONE_TOP;
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN
                        && (row + 1) * COLUMNS < gridApps.size()) {
                    gridIdx = Math.min(gridIdx + COLUMNS, gridApps.size() - 1);
                }
                break;
            }
            case ZONE_MENU: {
                int dir = code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 0;
                if (dir == 0) return;
                int next = nextEnabledMenuItem(menuIdx, dir);
                if (next >= 0) menuIdx = next;
                else if (dir < 0 && menuClosesOnUp) closeMenu();
                updateMenuSelection();
                return;
            }
        }
        if (zone * 1000 + index() != before) applyFocus(true);
    }

    private int index() {
        return zone == ZONE_TOP ? topIdx : zone == ZONE_DOCK ? dockIdx : zone == ZONE_GRID ? gridIdx
                : zone == ZONE_SRC ? srcIdx : menuIdx;
    }

    private void select() {
        if (moving) {
            finishMove();
            return;
        }
        switch (zone) {
            case ZONE_TOP:
                if (topIdx == PILL_INPUTS) openInputsMenu();
                else if (topIdx == PILL_WIFI) {
                    Intent wifi = new Intent(Settings.ACTION_WIFI_SETTINGS);
                    if (wifi.resolveActivity(getPackageManager()) == null) wifi = new Intent(Settings.ACTION_SETTINGS);
                    safeStart(wifi);
                } else safeStart(new Intent(Settings.ACTION_SETTINGS));
                break;
            case ZONE_DOCK:
                if (!dockApps.isEmpty()) launch(dockApps.get(dockIdx));
                break;
            case ZONE_GRID:
                if (!gridApps.isEmpty()) launch(gridApps.get(gridIdx));
                break;
            case ZONE_SRC:
                switchSource(srcIdx);
                break;
            case ZONE_MENU:
                if (menuIdx < menuItems.size() && menuItems.get(menuIdx).enabled) {
                    MenuItem item = menuItems.get(menuIdx);
                    closeMenu();
                    item.action.run();
                }
                break;
        }
    }

    private void longPress() {
        if (moving || zone == ZONE_MENU) return;
        if ((zone == ZONE_DOCK || zone == ZONE_GRID) && focusedApp() != null && focusedTile != null) {
            openAppMenu(focusedApp());
        } else {
            select();   // pills have no menu: a long press is just a press
        }
    }

    private void back() {
        if (moving) finishMove();
        else if (zone == ZONE_MENU) closeMenu();
        else if ((zone == ZONE_GRID || zone == ZONE_SRC) && !dockApps.isEmpty()) {
            zone = ZONE_DOCK;
            applyFocus(true);
        }
    }

    // ---------------------------------------------------------------- focus visuals

    /** The zone the screen is showing; while a menu is open that's where it was opened from. */
    private int visibleZone() {
        return zone == ZONE_MENU ? menuReturnZone : zone;
    }

    private AppEntry focusedApp() {
        int z = visibleZone();
        if (z == ZONE_DOCK && dockIdx < dockApps.size()) return dockApps.get(dockIdx);
        if (z == ZONE_GRID && gridIdx < gridApps.size()) return gridApps.get(gridIdx);
        return null;
    }

    private void applyFocus(boolean animate) {
        int z = visibleZone();
        View tile = null, target;
        float radius;
        if (z == ZONE_DOCK && dockIdx < dockView.getChildCount()) {
            tile = dockView.getChildAt(dockIdx);
        } else if (z == ZONE_GRID) {
            RecyclerView.LayoutManager lm = grid.getLayoutManager();
            tile = lm == null ? null : lm.findViewByPosition(gridIdx);
        } else if (z == ZONE_SRC) {
            tile = srcTiles[srcIdx];
        }
        float scale = moving ? MOVE_SCALE : TILE_SCALE;

        if (focusedTile != null && focusedTile != tile) setTileFocused(focusedTile, 1f, animate);
        if (tile != null) setTileFocused(tile, scale, animate);
        focusedTile = tile;

        for (int i = 0; i < pills.length; i++) {
            boolean on = z == ZONE_TOP && i == topIdx;
            ((TextView) pills[i].findViewById(R.id.pill_text)).setTextColor(on ? 0xFFFFFFFF : 0xD9FFFFFF);
            pills[i].findViewById(R.id.pill_icon).setAlpha(on ? 1f : 0.85f);
        }

        // Highlight target in content coordinates
        Rect r = new Rect();
        if (tile != null) {
            target = tile.findViewById(R.id.card);
            target.getDrawingRect(r);
            content.offsetDescendantRectToMyCoords(target, r);
            float pad = 9 * dp;
            float gx = r.width() * (scale - 1f) / 2f + pad, gy = r.height() * (scale - 1f) / 2f + pad;
            highlight.moveTo(new RectF(r.left - gx, r.top - gy, r.right + gx, r.bottom + gy),
                    14 * dp * scale + pad, animate);
        } else if (z == ZONE_TOP) {
            target = pills[topIdx];
            target.getDrawingRect(r);
            content.offsetDescendantRectToMyCoords(target, r);
            float g = 5 * dp;
            radius = (r.height() + 2 * g) / 2f;
            highlight.moveTo(new RectF(r.left - g, r.top - g, r.right + g, r.bottom + g), radius, animate);
        }

        // Dock title + ambient glow follow the dock focus; dimmed while the top bar is focused
        if (z == ZONE_DOCK && dockIdx < dockApps.size()) {
            AppEntry d = dockApps.get(dockIdx);
            if (!d.label.contentEquals(heroTitle.getText())) {
                heroTitle.setText(d.label);
                if (animate) {
                    heroTitle.setAlpha(0f);
                    heroTitle.setTranslationY(8 * dp);
                    heroTitle.animate().translationY(0).setDuration(260)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }
            }
            glow.setGlowColor(d.color, animate);
        }
        float titleAlpha = z == ZONE_DOCK ? 1f : 0.45f;
        heroTitle.animate().alpha(titleAlpha).setDuration(animate ? 260 : 0).start();
        glow.animate().alpha(z == ZONE_DOCK ? 1f : 0.5f).setDuration(animate ? 260 : 0).start();
        heroHint.animate().alpha(moving ? 1f : 0f).setDuration(animate ? 200 : 0).start();

        scrollForFocus(z, tile, animate);
        saveFocusState();
    }

    private void setTileFocused(View tile, float scale, boolean animate) {
        View card = tile.findViewById(R.id.card);
        View name = tile.findViewById(R.id.name);
        card.animate().scaleX(scale).scaleY(scale).setDuration(animate ? 260 : 0)
                .setInterpolator(new OvershootInterpolator(1.4f)).start();
        boolean focused = scale > 1f;
        if (name.getVisibility() == View.VISIBLE) {
            name.animate().alpha(focused ? 1f : 0f).setDuration(animate ? 200 : 0).start();
        }
        tile.setTranslationZ(focused ? 1f : 0f);
    }

    private void scrollForFocus(int z, View tile, boolean animate) {
        int y = 0;
        if (z == ZONE_GRID && tile != null) {
            Rect r = new Rect();
            tile.getDrawingRect(r);
            content.offsetDescendantRectToMyCoords(tile, r);
            View first = grid.getLayoutManager() == null ? null : grid.getLayoutManager().findViewByPosition(0);
            int firstTop = r.top;
            if (first != null) {
                Rect f = new Rect();
                first.getDrawingRect(f);
                content.offsetDescendantRectToMyCoords(first, f);
                firstTop = f.top;
            }
            // First row sits near the top with the dock just off screen; later rows only
            // scroll as much as needed to keep the focused row fully visible.
            int anchor = firstTop - Math.round(68 * dp);
            int needed = r.bottom - screenH + Math.round(72 * dp);
            y = Math.max(anchor, needed);
        }
        // Slide the content rather than scrolling the ScrollView: a ScrollView smooth-scroll
        // still running would override a later instant scrollTo, while a new translation
        // animation always replaces the previous one.
        if (animate) {
            content.animate().translationY(-y).setDuration(320)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        } else {
            content.animate().cancel();
            content.setTranslationY(-y);
        }
    }

    private void saveFocusState() {
        prefs.edit()
                .putInt("zone", visibleZone())
                .putInt("top", topIdx)
                .putInt("dock", dockIdx)
                .putString("grid", gridApps.isEmpty() ? null : gridApps.get(gridIdx).packageName)
                .apply();
    }

    private void restoreFocusState() {
        zone = prefs.getInt("zone", ZONE_DOCK);
        if (zone == ZONE_MENU) zone = ZONE_TOP;
        if (zone == ZONE_SRC) zone = ZONE_DOCK;   // the card index isn't saved
        topIdx = clamp(prefs.getInt("top", PILL_INPUTS), 0, pills.length - 1);
        dockIdx = prefs.getInt("dock", 0);
        savedGridPackage = prefs.getString("grid", null);
    }

    // ---------------------------------------------------------------- sources

    private static ColorMatrixColorFilter grayscale() {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0f);
        return new ColorMatrixColorFilter(m);
    }

    private void setupSources() {
        tvInputs = getSystemService(TvInputManager.class);
        sourcesView = findViewById(R.id.sources);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < SOURCES.length; i++) {
            View tile = inflater.inflate(R.layout.item_source, sourcesView, false);
            tile.findViewById(R.id.card).setClipToOutline(true);
            ((ImageView) tile.findViewById(R.id.banner)).setImageBitmap(loadSourceImage(SOURCES[i][2]));
            TextView port = tile.findViewById(R.id.port);
            port.setTypeface(fontRegular);
            port.setText("HDMI " + SOURCES[i][1]);
            sourcesView.addView(tile);
            srcTiles[i] = tile;
        }
        updateSources();
    }

    /** Decodes an asset at roughly card size; the originals are far bigger than the TV needs. */
    private Bitmap loadSourceImage(String file) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getAssets().open("sources/" + file)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1, o.outWidth / Math.round(150 * dp))));
            try (InputStream in = getAssets().open("sources/" + file)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (IOException ex) {
            return null;
        }
    }

    /** HDMI 1..3 as the TV input framework lists them (physical ports, not CEC children). */
    private String hdmiInputId(int port) {
        if (tvInputs == null) return null;
        List<TvInputInfo> hdmi = new ArrayList<>();
        for (TvInputInfo i : tvInputs.getTvInputList()) {
            if (i.getType() == TvInputInfo.TYPE_HDMI && i.getParentId() == null && !i.isHidden(this)) hdmi.add(i);
        }
        hdmi.sort((a, b) -> label(a).compareTo(label(b)));
        return port >= 1 && port <= hdmi.size() ? hdmi.get(port - 1).getId() : null;
    }

    /** Colour + green dot while the input is connected and on; grey and dim otherwise. */
    private void updateSources() {
        for (int i = 0; i < SOURCES.length; i++) {
            String id = hdmiInputId(Integer.parseInt(SOURCES[i][1]));
            boolean active = id != null && tvInputs.getInputState(id) == TvInputManager.INPUT_STATE_CONNECTED;
            ImageView img = srcTiles[i].findViewById(R.id.banner);
            img.setColorFilter(active ? null : GRAYSCALE);
            img.setAlpha(active ? 1f : 0.4f);
            srcTiles[i].findViewById(R.id.dot).getBackground()
                    .setTint(active ? 0xFF34C759 : 0x59FFFFFF);
            srcTiles[i].findViewById(R.id.port).setAlpha(active ? 1f : 0.6f);
        }
    }

    private void switchSource(int index) {
        String id = hdmiInputId(Integer.parseInt(SOURCES[index][1]));
        if (id == null) {
            Toast.makeText(this, "HDMI " + SOURCES[index][1] + " non disponibile", Toast.LENGTH_SHORT).show();
            return;
        }
        safeStart(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)));
    }

    // ---------------------------------------------------------------- top bar

    private void setupPill(int index, int id, int icon, String text) {
        View pill = findViewById(id);
        ((ImageView) pill.findViewById(R.id.pill_icon)).setImageResource(icon);
        TextView t = pill.findViewById(R.id.pill_text);
        t.setText(text);
        t.setTypeface(fontRegular);
        pills[index] = pill;
    }

    private void updateNetworkPill() {
        Network n = connectivity.getActiveNetwork();
        NetworkCapabilities c = n == null ? null : connectivity.getNetworkCapabilities(n);
        String text;
        if (c == null) text = getString(R.string.offline);
        else if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) text = getString(R.string.ethernet);
        else {
            text = getString(R.string.wifi);
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid != null && !ssid.isEmpty() && !WifiManager.UNKNOWN_SSID.equals(ssid)) {
                text = ssid.replaceAll("^\"|\"$", "");
            }
        }
        wifiText.setText(text);
        if (zone == ZONE_TOP && topIdx == PILL_WIFI) content.post(() -> applyFocus(true));
    }

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private void updateClock() {
        Locale it = Locale.ITALIAN;
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", it).format(now));
        String d = new SimpleDateFormat("EEEE d MMMM", it).format(now);
        date.setText(d.substring(0, 1).toUpperCase(it) + d.substring(1));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h >= 5 && h < 13 ? R.string.greet_morning
                : h >= 13 && h < 18 ? R.string.greet_afternoon : R.string.greet_evening);
    }

    // ---------------------------------------------------------------- popup menu

    /** One row of the popup: an input to switch to, or an action on an app. */
    private static final class MenuItem {
        final String label;
        final boolean enabled;
        final int dotColor;       // 0 = no dot
        final Runnable action;

        MenuItem(String label, boolean enabled, int dotColor, Runnable action) {
            this.label = label;
            this.enabled = enabled;
            this.dotColor = dotColor;
            this.action = action;
        }
    }

    private void openInputsMenu() {
        TvInputManager tim = getSystemService(TvInputManager.class);
        if (tim == null) return;
        List<TvInputInfo> all = tim.getTvInputList();
        Set<String> hardwarePackages = new HashSet<>();
        for (TvInputInfo i : all) if (i.isPassthroughInput()) hardwarePackages.add(i.getServiceInfo().packageName);

        List<TvInputInfo> hdmi = new ArrayList<>(), av = new ArrayList<>();
        TvInputInfo tuner = null;
        for (TvInputInfo i : all) {
            if (i.isHidden(this) || i.getParentId() != null) continue;
            int t = i.getType();
            if (t == TvInputInfo.TYPE_HDMI) hdmi.add(i);
            else if (t == TvInputInfo.TYPE_COMPOSITE || t == TvInputInfo.TYPE_COMPONENT
                    || t == TvInputInfo.TYPE_SVIDEO || t == TvInputInfo.TYPE_SCART
                    || t == TvInputInfo.TYPE_VGA) av.add(i);
            else if (t == TvInputInfo.TYPE_TUNER && tuner == null
                    && hardwarePackages.contains(i.getServiceInfo().packageName)) tuner = i;
        }
        hdmi.sort((a, b) -> label(a).compareTo(label(b)));
        List<TvInputInfo> inputs = new ArrayList<>(hdmi);
        inputs.addAll(av);
        if (tuner != null) inputs.add(tuner);
        if (inputs.isEmpty()) return;

        List<MenuItem> items = new ArrayList<>();
        for (TvInputInfo i : inputs) {
            boolean isTuner = i.getType() == TvInputInfo.TYPE_TUNER;
            // Dot shows whether something is plugged in and on
            int dot = isTuner ? 0 : tim.getInputState(i.getId()) == TvInputManager.INPUT_STATE_CONNECTED
                    ? 0xFF34C759 : 0x40FFFFFF;
            items.add(new MenuItem(isTuner ? getString(R.string.live_tv) : label(i), true, dot,
                    () -> safeStart(new Intent(Intent.ACTION_VIEW,
                            TvContract.buildChannelUriForPassthroughInput(i.getId())))));
        }
        showMenu(null, items, pills[PILL_INPUTS], false, true);
    }

    private void openAppMenu(AppEntry app) {
        List<MenuItem> items = new ArrayList<>();
        boolean inDock = zone == ZONE_DOCK;
        if (inDock) {
            items.add(new MenuItem(getString(R.string.menu_move), dockApps.size() > 1, 0, this::startMove));
            items.add(new MenuItem(getString(R.string.menu_remove_dock), true, 0, () -> removeFromDock(app)));
        } else {
            boolean full = dockApps.size() >= DOCK_MAX;
            items.add(new MenuItem(getString(full ? R.string.menu_dock_full : R.string.menu_add_dock),
                    !full, 0, () -> addToDock(app)));
        }
        items.add(new MenuItem(getString(R.string.menu_info), true, 0, () -> safeStart(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", app.packageName, null)))));
        // Preinstalled apps can only be disabled, not uninstalled, so they get no Uninstall
        if (!app.system) {
            items.add(new MenuItem(getString(R.string.menu_uninstall), true, 0, () -> safeStart(
                    new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null)))));
        }
        // Anchor on the whole tile so the menu clears the name under a grid tile. Dock tiles
        // sit at the bottom of the screen, so their menu opens above.
        showMenu(app.label, items, focusedTile, inDock, false);
    }

    /**
     * Shows the glass popup next to {@code anchor}: centred on it (left-aligned for the
     * Inputs pill), below it unless {@code above} or there's no room.
     */
    private void showMenu(String title, List<MenuItem> items, View anchor, boolean above, boolean fromPill) {
        menuItems.clear();
        menuItems.addAll(items);
        popup.removeAllViews();
        int ph = Math.round(16 * dp), pv = Math.round(11 * dp);

        menuHeaderCount = 0;
        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setTextColor(0x8CFFFFFF);
            t.setTypeface(fontRegular);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(ph, Math.round(6 * dp), ph, Math.round(4 * dp));
            popup.addView(t);
            menuHeaderCount = 1;
        }
        for (MenuItem item : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ph, pv, ph, pv);

            TextView name = new TextView(this);
            name.setText(item.label);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            name.setTextColor(item.enabled ? 0xF2FFFFFF : 0x59FFFFFF);
            name.setTypeface(fontRegular);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (item.dotColor != 0) {
                View dot = new View(this);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(item.dotColor);
                dot.setBackground(g);
                int s = Math.round(7 * dp);
                row.addView(dot, new LinearLayout.LayoutParams(s, s));
            }
            popup.addView(row);
        }

        // Measure, then place next to the anchor and keep it on screen
        int width = popup.getLayoutParams().width;
        popup.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = popup.getMeasuredHeight(), gap = Math.round(14 * dp), margin = Math.round(16 * dp);
        int screenW = getWindow().getDecorView().getWidth();
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);   // includes the tile's focus scale
        float aw = anchor.getWidth() * anchor.getScaleX(), ah = anchor.getHeight() * anchor.getScaleY();
        int x = fromPill ? loc[0] : Math.round(loc[0] + (aw - width) / 2f);
        x = clamp(x, margin, screenW - width - margin);
        int below = Math.round(loc[1] + ah) + gap, aboveY = loc[1] - gap - h;
        int y = above ? aboveY : below;
        if (!above && y + h > screenH - margin) y = aboveY;
        if (above && y < margin) y = below;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) popup.getLayoutParams();
        lp.leftMargin = x;
        lp.topMargin = y;
        popup.setLayoutParams(lp);

        menuReturnZone = zone;
        menuClosesOnUp = fromPill;
        zone = ZONE_MENU;
        menuIdx = Math.max(0, nextEnabledMenuItem(-1, 1));
        updateMenuSelection();
        popup.setAlpha(0f);
        popup.setTranslationY((above ? 8 : -8) * dp);
        popup.setVisibility(View.VISIBLE);
        popup.animate().alpha(1f).translationY(0).setDuration(180).start();
    }

    private int nextEnabledMenuItem(int from, int dir) {
        for (int k = from + dir; k >= 0 && k < menuItems.size(); k += dir) {
            if (menuItems.get(k).enabled) return k;
        }
        return -1;
    }

    private String label(TvInputInfo i) {
        CharSequence custom = i.loadCustomLabel(this);
        return String.valueOf(custom != null && custom.length() > 0 ? custom : i.loadLabel(this));
    }

    private void updateMenuSelection() {
        if (zone != ZONE_MENU) return;
        for (int k = 0; k < menuItems.size(); k++) {
            popup.getChildAt(k + menuHeaderCount)
                    .setBackgroundResource(k == menuIdx ? R.drawable.menu_item_focused : 0);
        }
    }

    private void closeMenu() {
        if (popup.getVisibility() != View.VISIBLE) return;
        popup.setVisibility(View.GONE);
        if (zone == ZONE_MENU) {
            zone = menuReturnZone;
            applyFocus(true);
        }
    }

    // ---------------------------------------------------------------- dock editing

    private void startMove() {
        if (dockApps.size() < 2) return;
        moving = true;
        applyFocus(true);
    }

    private void finishMove() {
        moving = false;
        writeDock(dockPackages());
        applyFocus(true);
    }

    private void swapDock(int from, int to) {
        Collections.swap(dockApps, from, to);
        TileAdapter.bind(dockView.getChildAt(from), dockApps.get(from), fontRegular);
        TileAdapter.bind(dockView.getChildAt(to), dockApps.get(to), fontRegular);
        focusedTile = null;   // bind() reset both tiles' scale
        dockIdx = to;
        applyFocus(true);
    }

    private void addToDock(AppEntry app) {
        List<String> packages = dockPackages();
        if (packages.size() >= DOCK_MAX || packages.contains(app.packageName)) return;
        packages.add(app.packageName);
        writeDock(packages);
        // Follow the app up into the dock
        zone = ZONE_DOCK;
        dockIdx = packages.size() - 1;
        reloadApps();
    }

    private void removeFromDock(AppEntry app) {
        List<String> packages = dockPackages();
        packages.remove(app.packageName);
        writeDock(packages);
        zone = ZONE_DOCK;
        dockIdx = Math.max(0, Math.min(dockIdx, packages.size() - 1));
        reloadApps();
    }

    private List<String> dockPackages() {
        List<String> list = new ArrayList<>();
        for (AppEntry e : dockApps) list.add(e.packageName);
        return list;
    }

    /** Saves the dock to files/dock.txt, the same file the README tells you to push. */
    private void writeDock(List<String> packages) {
        File f = AppLoader.dockFile(this);
        try (FileWriter w = new FileWriter(f)) {
            for (String p : packages) w.write(p + "\n");
        } catch (IOException ex) {
            Toast.makeText(this, ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
        dockFileStamp = f.lastModified();
    }

    // ---------------------------------------------------------------- wallpaper

    private void loadWallpaperIfChanged() {
        File f = AppLoader.wallpaperFile(this);
        long stamp = f.isFile() ? f.lastModified() : 0;
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;
        Bitmap bmp = null;
        if (stamp != 0) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outHeight / (sample * 2) >= screenH) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory; it is dimmed anyway
            bmp = BitmapFactory.decodeFile(f.getPath(), o);
        }
        wallpaper.setImageBitmap(bmp);
        int vis = bmp == null ? View.GONE : View.VISIBLE;
        wallpaper.setVisibility(vis);
        wallpaperDim.setVisibility(vis);
    }

    // ---------------------------------------------------------------- utils

    private Typeface font(int weight) {
        try {
            return new Typeface.Builder(getAssets(), "fonts/Outfit.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
        } catch (Exception e) {
            return Typeface.create("sans-serif-light", Typeface.NORMAL);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return hi < lo ? 0 : Math.max(lo, Math.min(hi, v));
    }
}

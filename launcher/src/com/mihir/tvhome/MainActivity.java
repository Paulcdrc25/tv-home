package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.animation.ObjectAnimator;
import android.view.animation.LinearInterpolator;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX), no network, no background work.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    // Dock order: the user's own (saved from the remote) or config.xml's.
    private final List<String> dockApps = new ArrayList<>();
    private String focusPkg;
    static final int DOCK_MAX = 9;
    // Own photos go here (tools/add-wallpaper.sh); shown after the built-in ones.
    static final String PHOTO_DIR = "/sdcard/Pictures/TVHome";
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private List<String> wallpapers = new ArrayList<>();
    private boolean rotate = true;
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView clock, title, appsLabel;
    // iOS-style world clock widget, top-left.
    static final java.util.TimeZone WORLD_TZ = java.util.TimeZone.getTimeZone("Pacific/Tahiti");
    static final String WORLD_CITY = "Tahiti";
    private ClockFace worldFace;
    private TextView worldTime;
    // Spotify widget: last / current track from the Web API (credentials sent by tools/spotify-connect.py).
    private ImageView spCover;
    private View spTile;
    private TextView spLabel;
    private ObjectAnimator spSpin;
    private String spToken, spCoverUrl;
    private long spTokenExpires;
    private final Runnable spotifyPoll = this::fetchSpotify;
    static final long SPOTIFY_POLL_MS = 30_000;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String saved = prefs().getString("dock", null);
        dockApps.addAll(saved != null
                ? Arrays.asList(saved.isEmpty() ? new String[0] : saved.split(","))
                : Arrays.asList(getResources().getStringArray(R.array.dock_apps)));
        rotate = prefs().getBoolean("rotate", true);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = getResources().getInteger(R.integer.columns);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        // 32-bit window so photos and gradients aren't dithered down.
        getWindow().setFormat(android.graphics.PixelFormat.RGBA_8888);
        setContentView(buildUi());
        buildApps();
        saveSpotifyCredentials(getIntent());

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, tf);
        updateClock();
        fetchSpotify();
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        loadWallpaperList();
        if (wallIndex < 0 || (rotate && SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS)) nextWallpaper();
        else if (rotate) scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        handler.removeCallbacks(spotifyPoll);
        if (spSpin != null) spSpin.pause();
        unregisterReceiver(tick);
        if (inputsPopup != null) inputsPopup.dismiss();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (saveSpotifyCredentials(intent)) { spToken = null; spCoverUrl = null; fetchSpotify(); }
        // Home pressed while already home: jump back to the first dock app.
        if (inputsPopup != null) inputsPopup.dismiss();
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, pills) and bottom (dock) so text stays readable.
        View scrim = new View(this);
        // Light scrim: just enough at the top and bottom for the widgets and dock, photo untouched in the middle.
        GradientDrawable scrimBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x40000000, 0x0A000000, 0x00000000, 0x26000000, 0x80000000});
        scrimBg.setDither(true);
        scrim.setBackground(scrimBg);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));

        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(36), dp(28), dp(36), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(32), 0, dp(32), dp(36)));

        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        title.setVisibility(View.GONE);
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(dp(8), 0, 0, dp(14));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(16), dp(18), dp(16), dp(18));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(24));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("ALL APPS");
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
    }

    /** Apple TV style: nothing but a small frosted cluster top-right (time, Sources, Settings). */
    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        TextView inputs = pill("Sources", "⇥");
        inputs.setOnClickListener(v -> showInputs(v));
        TextView walls = pill("Fond d'écran", "◐");
        walls.setOnClickListener(v -> showWallpapers());
        TextView settings = pill("Réglages", "⚙");
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        for (TextView p : new TextView[]{inputs, walls, settings}) {
            right.addView(p, llp(-2, dp(26), 0, 0, dp(8), 0));
            pills.add(p);
        }
        clock = text(12, "sans-serif-medium", 0xF2FFFFFF);
        clock.setGravity(Gravity.CENTER);
        clock.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(13));
        bg.setColor(0x59000000);
        clock.setBackground(bg);
        right.addView(clock, llp(-2, dp(26), 0, 0, 0, 0));
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        LinearLayout widgets = new LinearLayout(this);
        widgets.addView(buildWorldClock(), new LinearLayout.LayoutParams(dp(124), dp(124)));
        widgets.addView(buildSpotify(), llp(dp(124), dp(124), dp(12), 0, 0, 0));
        header.addView(widgets, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, String glyph) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(glyph + "  " + label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(14), 0, dp(14), 0);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return p;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }
        // Borrow another app's banner (config.xml banner_from: "app>source"), e.g. YouTube's for TizenTube.
        for (String rule : getResources().getStringArray(R.array.banner_from)) {
            String[] ps = rule.split(">");
            App a = byPkg.get(ps[0]);
            if (a == null || ps.length < 2) continue;
            try {
                Intent li = pm.getLeanbackLaunchIntentForPackage(ps[1]);
                Drawable b = li != null ? pm.getActivityBanner(li) : null;
                if (b == null) b = pm.getApplicationBanner(ps[1]);
                if (b != null) { a.art = b; a.isBanner = true; colorCache.remove(a.pkg); a.color = sampleColor(a.pkg, b); }
            } catch (PackageManager.NameNotFoundException ignored) { }
        }

        List<App> dock = new ArrayList<>();
        for (String p : dockApps) if (byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        for (int i = 0; i < dock.size(); i++) {
            View tile = tile(dock.get(i), true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i == 0 ? 0 : dp(7), 0, i == dock.size() - 1 ? 0 : dp(7), 0);
            dockRow.addView(tile, p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();

        if (!dock.isEmpty()) { title.setText(dock.get(0).label); setGlow(dock.get(0).color); }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (focusPkg != null) {
            List<View> all = new ArrayList<>(dockCards);
            all.addAll(gridCards);
            for (View c : all) if (focusPkg.equals(((App) c.getTag()).pkg)) last = c;
            focusPkg = null;
        }
        if (last != null) last.requestFocus();
        else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- dock editing

    /** Long-press OK (or Menu) on a tile: move it in the dock, take it out, or add it. */
    private void showAppMenu(View anchor, App app, boolean inDock) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (inDock) {
            int i = dockCards.indexOf(anchor);
            if (i > 0) { labels.add("←  Déplacer à gauche"); actions.add(() -> moveInDock(app.pkg, -1)); }
            if (i < dockCards.size() - 1) { labels.add("→  Déplacer à droite"); actions.add(() -> moveInDock(app.pkg, 1)); }
            labels.add("✕  Retirer du dock");
            actions.add(() -> editDock(d -> d.remove(app.pkg), null));
        } else {
            labels.add("＋  Ajouter au dock");
            actions.add(() -> {
                if (visibleDock().size() >= DOCK_MAX) {
                    Toast.makeText(this, "Dock plein (" + DOCK_MAX + " apps max)", Toast.LENGTH_SHORT).show();
                    return;
                }
                editDock(d -> d.add(app.pkg), app.pkg);
            });
        }
        menu(anchor, app.label, labels, actions);
    }

    /** Dock apps that are actually installed, in dock order. */
    private List<String> visibleDock() {
        List<String> out = new ArrayList<>();
        for (View c : dockCards) out.add(((App) c.getTag()).pkg);
        return out;
    }

    private void moveInDock(String pkg, int delta) {
        editDock(d -> {
            int i = d.indexOf(pkg), j = i + delta;
            if (i >= 0 && j >= 0 && j < d.size()) Collections.swap(d, i, j);
        }, pkg);
    }

    private void editDock(java.util.function.Consumer<List<String>> change, String focus) {
        List<String> d = visibleDock();
        change.accept(d);
        dockApps.clear();
        dockApps.addAll(d);
        prefs().edit().putString("dock", String.join(",", d)).apply();
        focusPkg = focus;
        lastFocusedId = View.NO_ID;
        buildApps();
    }

    /** Small frosted popup with one row per action, under the anchor. */
    private void menu(View anchor, String heading, List<String> labels, List<Runnable> actions) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);
        TextView head = text(10, "sans-serif-medium", 0x99FFFFFF);
        head.setText(heading.toUpperCase(Locale.getDefault()));
        head.setLetterSpacing(0.15f);
        head.setSingleLine(true);
        list.addView(head, llp(-1, -2, dp(14), dp(4), dp(14), dp(6)));

        PopupWindow pw = new PopupWindow(list, dp(220), -2, true);
        List<TextView> rows = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            Runnable a = actions.get(i);
            TextView r = inputRow(labels.get(i), a);
            r.setOnClickListener(v -> { pw.dismiss(); a.run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
            rows.add(r);
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        // Dock tiles sit at the bottom of the screen, so open the menu above them.
        list.measure(View.MeasureSpec.makeMeasureSpec(dp(220), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        pw.showAsDropDown(anchor, 0, -anchor.getHeight() - list.getMeasuredHeight() - dp(12), Gravity.START);
        rows.get(0).requestFocus();
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("home", MODE_PRIVATE);
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        card.setOnLongClickListener(v -> { showAppMenu(v, app, inDock); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? spTile.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        int topId = pills.get(0).getId();
        spTile.setNextFocusRightId(topId);
        spTile.setNextFocusDownId(d > 0 ? dockCards.get(0).getId() : spTile.getId());
        spTile.setNextFocusLeftId(spTile.getId());
        spTile.setNextFocusUpId(spTile.getId());
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            // The widgets sit above the left end of the dock.
            c.setNextFocusUpId(i < 2 ? spTile.getId() : topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(18)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    private void showInputs(View anchor) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(inputRow(inputLabel(t), () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        if (isInstalled(liveTvPkg)) rows.add(inputRow("Live TV", () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("All sources…", () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(inputRow("Settings", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { inputsPopup = null; anchor.requestFocus(); });
        inputsPopup = pw;
        pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm a", Locale.getDefault()).format(Calendar.getInstance().getTime()));
        updateWorldClock(is24);
    }

    /** Dark tile, big dark dial with thin grey hands, monospace time chip on the left. */
    private View buildWorldClock() {
        FrameLayout w = widgetTile();
        worldFace = new ClockFace(this);
        w.addView(worldFace, new FrameLayout.LayoutParams(-1, -1));
        worldFace.setPadding(dp(9), dp(9), dp(9), dp(9));
        worldTime = chip();
        w.addView(worldTime, lp(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL, dp(7), 0, 0, 0));
        return w;
    }

    private void updateWorldClock(boolean is24) {
        SimpleDateFormat f = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault());
        f.setTimeZone(WORLD_TZ);
        worldTime.setText(f.format(new java.util.Date()) + "\n" + WORLD_CITY.toUpperCase(Locale.ROOT));
        Calendar there = Calendar.getInstance(WORLD_TZ);
        worldFace.set(there.get(Calendar.HOUR_OF_DAY), there.get(Calendar.MINUTE));
    }

    private FrameLayout widgetTile() {
        FrameLayout w = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0x1FFFFFFF); // same frosted glass as the dock band
        bg.setStroke(dp(1), 0x14FFFFFF);
        w.setBackground(bg);
        w.setClipToOutline(true);
        w.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16)); }
        });
        return w;
    }

    /** Small dark label with monospace text, like the "10:08 PDT" chip. */
    private TextView chip() {
        TextView t = text(9.5f, "monospace", 0xF2FFFFFF);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        t.setLineSpacing(0, 0.95f);
        t.setPadding(dp(6), dp(4), dp(6), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(5));
        bg.setColor(0x8C000000);
        t.setBackground(bg);
        return t;
    }

    // ---------------------------------------------------------------- spotify

    /** Cover art as a spinning disc (while playing) on a dark tile, track chip at the bottom. */
    private View buildSpotify() {
        FrameLayout w = widgetTile();
        spCover = new ImageView(this);
        spCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        spCover.setBackgroundColor(0x40000000);
        spCover.setClipToOutline(true);
        spCover.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        w.addView(spCover, lp(-1, -1, Gravity.CENTER, dp(8), dp(8), dp(8), dp(8)));
        // Spindle hole, so the cover reads as a record.
        View hole = new View(this);
        GradientDrawable hd = new GradientDrawable();
        hd.setShape(GradientDrawable.OVAL);
        hd.setColor(0xD9000000);
        hd.setStroke(dp(1.5f), 0x55FFFFFF);
        hole.setBackground(hd);
        w.addView(hole, new FrameLayout.LayoutParams(dp(9), dp(9), Gravity.CENTER));
        spLabel = chip();
        spLabel.setMaxWidth(dp(110));
        spLabel.setMaxLines(2);
        spLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        w.addView(spLabel, lp(-2, -2, Gravity.START | Gravity.BOTTOM, dp(7), 0, dp(7), dp(7)));
        spLabel.setText(prefs().getString("sp_refresh", null) == null ? "SPOTIFY\nNON CONNECTÉ" : "SPOTIFY");
        spSpin = ObjectAnimator.ofFloat(spCover, View.ROTATION, 0f, 360f);
        spSpin.setDuration(24_000);
        spSpin.setRepeatCount(ObjectAnimator.INFINITE);
        spSpin.setInterpolator(new LinearInterpolator());

        // Focusable: Left from Sources or Up from the first dock apps; OK opens Spotify.
        spTile = w;
        w.setId(View.generateViewId());
        w.setFocusable(true);
        w.setClickable(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(16));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        w.setForeground(ring);
        w.getForeground().setAlpha(0);
        w.setOnClickListener(v -> openPackage("com.spotify.tv.android"));
        w.setOnFocusChangeListener((v, has) -> {
            v.getForeground().setAlpha(has ? 255 : 0);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                    .setInterpolator(new OvershootInterpolator(1.8f)).setDuration(260).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return w;
    }

    /** tools/spotify-connect.py starts the home screen with these extras once, after the OAuth dance. */
    private boolean saveSpotifyCredentials(Intent i) {
        if (i == null || i.getStringExtra("sp_refresh") == null) return false;
        prefs().edit()
                .putString("sp_id", i.getStringExtra("sp_id"))
                .putString("sp_secret", i.getStringExtra("sp_secret"))
                .putString("sp_refresh", i.getStringExtra("sp_refresh"))
                .apply();
        i.removeExtra("sp_secret");
        i.removeExtra("sp_refresh");
        return true;
    }

    /** Current track if something is playing, else the last one played. Polls while home is visible. */
    private void fetchSpotify() {
        handler.removeCallbacks(spotifyPoll);
        android.content.SharedPreferences sp = prefs();
        String id = sp.getString("sp_id", null), secret = sp.getString("sp_secret", null), refresh = sp.getString("sp_refresh", null);
        if (id == null || secret == null || refresh == null) return;
        new Thread(() -> {
            try {
                if (spToken == null || SystemClock.elapsedRealtime() > spTokenExpires) {
                    org.json.JSONObject t = new org.json.JSONObject(http("POST", "https://accounts.spotify.com/api/token",
                            "Basic " + android.util.Base64.encodeToString((id + ":" + secret).getBytes(), android.util.Base64.NO_WRAP),
                            "grant_type=refresh_token&refresh_token=" + java.net.URLEncoder.encode(refresh, "UTF-8")));
                    spToken = t.getString("access_token");
                    spTokenExpires = SystemClock.elapsedRealtime() + (t.optLong("expires_in", 3600) - 60) * 1000;
                    if (t.has("refresh_token")) sp.edit().putString("sp_refresh", t.getString("refresh_token")).apply();
                }
                org.json.JSONObject track = null;
                boolean playing = false;
                String now = http("GET", "https://api.spotify.com/v1/me/player/currently-playing", "Bearer " + spToken, null);
                if (now != null && !now.isEmpty()) {
                    org.json.JSONObject o = new org.json.JSONObject(now);
                    track = o.optJSONObject("item");
                    playing = o.optBoolean("is_playing");
                }
                if (track == null) {
                    org.json.JSONArray items = new org.json.JSONObject(http("GET",
                            "https://api.spotify.com/v1/me/player/recently-played?limit=1", "Bearer " + spToken, null)).getJSONArray("items");
                    if (items.length() > 0) track = items.getJSONObject(0).getJSONObject("track");
                }
                if (track == null) return;
                String name = track.optString("name");
                org.json.JSONArray artists = track.optJSONArray("artists");
                String artist = artists != null && artists.length() > 0 ? artists.getJSONObject(0).optString("name") : "";
                org.json.JSONArray images = track.getJSONObject("album").optJSONArray("images");
                // images[1] is ~300 px: plenty for a 124 dp disc.
                String url = images == null || images.length() == 0 ? null
                        : images.getJSONObject(Math.min(1, images.length() - 1)).getString("url");
                Bitmap cover = null;
                if (url != null && !url.equals(spCoverUrl)) {
                    try (java.io.InputStream in = new java.net.URL(url).openStream()) { cover = BitmapFactory.decodeStream(in); }
                }
                Bitmap c = cover;
                boolean p = playing;
                String label = (p ? "▶ " : "") + name + "\n" + artist;
                runOnUiThread(() -> showSpotify(label, c, url, p));
            } catch (Exception e) {
                spToken = null; // retried on the next poll
                android.util.Log.w("tvhome", "spotify: " + e);
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                runOnUiThread(() -> { if (spCoverUrl == null) spLabel.setText("SPOTIFY\n" + msg.toUpperCase(Locale.ROOT)); });
            }
        }).start();
        handler.postDelayed(spotifyPoll, SPOTIFY_POLL_MS);
    }

    private void showSpotify(String label, Bitmap cover, String url, boolean playing) {
        if (isDestroyed()) return;
        spLabel.setText(label.toUpperCase(Locale.getDefault()));
        if (cover != null) {
            spCoverUrl = url;
            spCover.setImageBitmap(cover);
            spCover.setAlpha(0f);
            spCover.animate().alpha(1f).setDuration(400).start();
        }
        if (playing) { if (!spSpin.isStarted()) spSpin.start(); else spSpin.resume(); }
        else if (spSpin.isStarted()) spSpin.pause();
    }

    /** Tiny blocking HTTP helper; returns null on 204. */
    private static String http(String method, String url, String auth, String form) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Authorization", auth);
        if (form != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.getOutputStream().write(form.getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        if (code == 204) return null;
        if (code >= 400) {
            String body = "";
            try (java.io.InputStream es = c.getErrorStream()) {
                if (es != null) body = new java.util.Scanner(es, "UTF-8").useDelimiter("\\A").next();
            } catch (RuntimeException ignored) { }
            android.util.Log.w("tvhome", "spotify " + url + " -> " + code + " " + body);
            throw new java.io.IOException("HTTP " + code);
        }
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** Minimal dark dial: no numbers or ticks, thin grey hands, hollow centre. */
    static final class ClockFace extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int hour, minute;

        ClockFace(Context c) { super(c); }

        void set(int h, int m) { hour = h; minute = m; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth() - getPaddingLeft() - getPaddingRight();
            float r = w / 2f, cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x47000000);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            hand(c, cx, cy, (hour % 12 + minute / 60f) * 30f, r * 0.42f, r * 0.045f, 0xFFD6D6D6);
            hand(c, cx, cy, minute * 6f, r * 0.80f, r * 0.022f, 0xFFC4C4C4);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF3A3A3A);
            c.drawCircle(cx, cy, r * 0.055f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.022f);
            p.setColor(0xFFC4C4C4);
            c.drawCircle(cx, cy, r * 0.055f, p);
        }

        private void hand(Canvas c, float cx, float cy, float deg, float len, float width, int color) {
            double a = Math.toRadians(deg);
            p.setColor(color);
            p.setStrokeWidth(width);
            float s = len * 0.07f; // start just off the hub, like the reference
            c.drawLine(cx + (float) Math.sin(a) * s, cy - (float) Math.cos(a) * s,
                    cx + (float) Math.sin(a) * len, cy - (float) Math.cos(a) * len, p);
        }
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.78f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        g.setColors(new int[]{withAlpha(color, 0.14f), withAlpha(color, 0.04f), Color.TRANSPARENT});
        g.setDither(true);
        glow.setBackground(g);
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Built-in photos, then the user's own from PHOTO_DIR. */
    private void loadWallpaperList() {
        List<String> list = new ArrayList<>();
        try {
            for (String n : getAssets().list("wallpapers")) list.add("asset:wallpapers/" + n);
        } catch (java.io.IOException ignored) { }
        java.io.File[] own = new java.io.File(PHOTO_DIR).listFiles((d, n) -> n.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp)"));
        if (own != null) {
            Arrays.sort(own);
            for (java.io.File f : own) list.add(f.getAbsolutePath());
        }
        String current = wallIndex >= 0 && wallIndex < wallpapers.size() ? wallpapers.get(wallIndex) : null;
        wallpapers = list;
        if (current != null) wallIndex = wallpapers.indexOf(current);
    }

    /** Next photo in the rotation; on first show, the saved one when rotation is off. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.isEmpty()) return;
        if (wallIndex < 0) {
            int saved = wallpapers.indexOf(prefs().getString("wallpaper", ""));
            if (!rotate && saved >= 0) { showWallpaperAt(saved, true); return; }
            wallIndex = saved;
        }
        showWallpaperAt((wallIndex + 1) % wallpapers.size(), false);
    }

    /** Decode the photo off the main thread, then crossfade it in over the current one. */
    private void showWallpaperAt(int index, boolean quick) {
        handler.removeCallbacks(rotateWallpaper);
        wallIndex = index;
        String path = wallpapers.get(index);
        prefs().edit().putString("wallpaper", path).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        int w = getResources().getDisplayMetrics().widthPixels;
        new Thread(() -> {
            Bitmap bm = decode(path, w);
            runOnUiThread(() -> showWallpaper(bm, quick));
        }).start();
        if (rotate) scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    private java.io.InputStream open(String path) throws java.io.IOException {
        return path.startsWith("asset:") ? getAssets().open(path.substring(6)) : new java.io.FileInputStream(path);
    }

    /** Decode at roughly the given width, so big camera photos don't blow up memory. */
    private Bitmap decode(String path, int width) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = open(path)) { BitmapFactory.decodeStream(in, null, o); }
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            // Full 8-bit colour (565 bands smooth gradients) and convert any embedded profile to sRGB.
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB);
            try (java.io.InputStream in = open(path)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** Bottom sheet: rotation on/off and a strip of thumbnails; picking one shows it right away. */
    private void showWallpapers() {
        loadWallpaperList();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(0xE61C1C1E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        panel.setBackground(bg);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text(15, "sans-serif-medium", Color.WHITE);
        heading.setText("Fond d'écran");
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView toggle = inputRow("", null);
        toggle.setText(rotate ? "↻  Rotation auto : 10 min" : "↻  Rotation auto : non");
        toggle.setOnClickListener(v -> {
            rotate = !rotate;
            prefs().edit().putBoolean("rotate", rotate).apply();
            toggle.setText(rotate ? "↻  Rotation auto : 10 min" : "↻  Rotation auto : non");
            if (rotate) scheduleWallpaper(WALLPAPER_INTERVAL_MS);
            else handler.removeCallbacks(rotateWallpaper);
        });
        top.addView(toggle, new LinearLayout.LayoutParams(-2, dp(34)));
        panel.addView(top, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(dp(6), dp(12), dp(6), dp(10));
        LinearLayout strip = new LinearLayout(this);
        strip.setClipChildren(false);
        hs.addView(strip);
        panel.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = text(10, "sans-serif", 0x80FFFFFF);
        hint.setText("Tes photos : dossier Pictures/TVHome de la TV (tools/add-wallpaper.sh depuis le Mac)");
        panel.addView(hint, llp(-2, -2, dp(4), dp(2), 0, 0));

        int sw = getResources().getDisplayMetrics().widthPixels;
        PopupWindow pw = new PopupWindow(panel, sw - dp(64), -2, true);
        List<View> thumbs = new ArrayList<>();
        int tw = dp(150), th = dp(84);
        for (int i = 0; i < wallpapers.size(); i++) {
            int index = i;
            FrameLayout t = new FrameLayout(this);
            t.setId(View.generateViewId());
            t.setFocusable(true);
            t.setClipToOutline(true);
            t.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); }
            });
            t.setBackgroundColor(0xFF2C2C2E);
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            t.addView(img, new FrameLayout.LayoutParams(-1, -1));
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(10));
            ring.setStroke(dp(3), Color.WHITE);
            View sel = new View(this);
            sel.setBackground(ring);
            sel.setAlpha(i == wallIndex ? 0.5f : 0f);
            t.addView(sel, new FrameLayout.LayoutParams(-1, -1));
            t.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? dp(8) : 0).setDuration(180).start();
                sel.setAlpha(has ? 1f : index == wallIndex ? 0.5f : 0f);
            });
            t.setOnClickListener(v -> {
                showWallpaperAt(index, true);
                for (int k = 0; k < thumbs.size(); k++) ((FrameLayout) thumbs.get(k)).getChildAt(1).setAlpha(k == index ? 1f : 0f);
            });
            strip.addView(t, llp(tw, th, i == 0 ? 0 : dp(14), 0, 0, 0));
            thumbs.add(t);
            String path = wallpapers.get(i);
            new Thread(() -> {
                Bitmap bm = decode(path, tw);
                runOnUiThread(() -> { if (bm != null) img.setImageBitmap(bm); });
            }).start();
        }
        for (int i = 0; i < thumbs.size(); i++) {
            View t = thumbs.get(i);
            t.setNextFocusLeftId(thumbs.get(Math.max(0, i - 1)).getId());
            t.setNextFocusRightId(thumbs.get(Math.min(thumbs.size() - 1, i + 1)).getId());
            t.setNextFocusUpId(toggle.getId());
            t.setNextFocusDownId(t.getId());
        }
        if (!thumbs.isEmpty()) toggle.setNextFocusDownId(thumbs.get(Math.max(0, wallIndex)).getId());
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { if (!pills.isEmpty()) pills.get(1).requestFocus(); });
        pw.showAtLocation(scroll, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(32));
        View first = thumbs.isEmpty() ? toggle : thumbs.get(Math.max(0, wallIndex));
        first.requestFocus();
        if (first != toggle) hs.post(() -> hs.smoothScrollTo(Math.max(0, first.getLeft() - dp(40)), 0));
    }

    private void showWallpaper(Bitmap bm, boolean quick) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWallpaper || quick ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Menu key on the remote: options for the focused app, otherwise the Inputs dropdown.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && !pills.isEmpty()) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof App) showAppMenu(f, (App) f.getTag(), dockCards.contains(f));
            else showInputs(pills.get(0));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}

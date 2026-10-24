package com.mihir.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
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
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * iOS-style app switcher (long-press Home): a card per app used since boot, most recent first.
 * Up closes the focused app (kills its background process), OK reopens it, Down reaches "Tout fermer".
 * Android doesn't let apps list other apps' tasks, so "open" means used since boot and not closed here.
 */
public class SwitcherActivity extends Activity {

    static final int MAX_CARDS = 12;

    static final class Card {
        String pkg, label;
        Intent launch;
        Drawable art;
        boolean isBanner;
    }

    private final List<View> cards = new ArrayList<>();
    private HorizontalScrollView scroll;
    private LinearLayout strip;
    private TextView ramText, empty, closeAll;
    private View ramFill;
    private ActivityManager am;
    private SharedPreferences closed;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        closed = getSharedPreferences("switcher_closed", MODE_PRIVATE);
        getWindow().setFormat(android.graphics.PixelFormat.RGBA_8888);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(buildUi());
        List<Card> open = openApps();
        for (Card c : open) strip.addView(card(c), llp(dp(280), -2, cards.isEmpty() ? 0 : dp(28), 0, 0, 0));
        empty.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
        closeAll.setVisibility(cards.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();
        updateRam();
        if (!cards.isEmpty()) cards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- data

    /** Apps brought to the foreground since boot, newest first, minus the ones closed here since. */
    private List<Card> openApps() {
        List<Card> out = new ArrayList<>();
        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis(), boot = now - SystemClock.elapsedRealtime();
        Map<String, Long> last = new HashMap<>();
        try {
            UsageEvents ev = usm.queryEvents(boot, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last.put(e.getPackageName(), e.getTimeStamp());
            }
        } catch (RuntimeException ignored) { }
        last.remove(getPackageName());

        PackageManager pm = getPackageManager();
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(last.entrySet());
        sorted.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        for (Map.Entry<String, Long> en : sorted) {
            String pkg = en.getKey();
            if (closed.getLong(pkg, 0) >= en.getValue()) continue;
            Intent li = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (li == null) continue; // system screens (settings, input pickers…)
            ResolveInfo ri = pm.resolveActivity(li, 0);
            if (ri == null) continue;
            ActivityInfo ai = ri.activityInfo;
            Card c = new Card();
            c.pkg = pkg;
            c.label = String.valueOf(ri.loadLabel(pm));
            c.launch = li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            c.isBanner = banner != null;
            c.art = banner != null ? banner : ri.loadIcon(pm);
            out.add(c);
            if (out.size() == MAX_CARDS) break;
        }
        return out;
    }

    private void close(View card) {
        Card c = (Card) card.getTag();
        am.killBackgroundProcesses(c.pkg);
        closed.edit().putLong(c.pkg, System.currentTimeMillis()).apply();
        int i = cards.indexOf(card);
        cards.remove(card);
        View box = (View) card.getParent();
        // Fling the card up, like swiping it away on iOS.
        box.animate().translationY(-dp(320)).alpha(0f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                    strip.removeView(box);
                    if (strip.getChildCount() > 0) ((ViewGroup.MarginLayoutParams) strip.getChildAt(0).getLayoutParams()).leftMargin = 0;
                    wireFocus();
                    if (cards.isEmpty()) {
                        empty.setVisibility(View.VISIBLE);
                        closeAll.setVisibility(View.GONE);
                        empty.requestFocus();
                    } else {
                        cards.get(Math.min(i, cards.size() - 1)).requestFocus();
                    }
                }).start();
        // The kernel frees the memory a moment later.
        ramText.postDelayed(this::updateRam, 700);
    }

    private void closeAll() {
        for (View c : new ArrayList<>(cards)) close(c);
    }

    private void updateRam() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        float used = 1f - (float) mi.availMem / mi.totalMem;
        ramText.setText(String.format(Locale.FRANCE, "MÉMOIRE LIBRE  %d Mo / %.1f Go",
                mi.availMem / (1024 * 1024), mi.totalMem / (1024f * 1024 * 1024)));
        View track = (View) ramFill.getParent();
        track.post(() -> {
            ViewGroup.LayoutParams lp = ramFill.getLayoutParams();
            lp.width = Math.round(track.getWidth() * used);
            ramFill.setLayoutParams(lp);
        });
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Blurred copy of the home wallpaper: decoded tiny, then stretched with filtering.
        ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        String wp = getSharedPreferences("home", MODE_PRIVATE).getString("wallpaper", null);
        if (wp != null) new Thread(() -> {
            Bitmap small = tiny(wp);
            runOnUiThread(() -> { if (small != null) bg.setImageBitmap(small); });
        }).start();
        // Frosted glass like the dock: a light white veil, a little darker at the top and bottom for text.
        View frost = new View(this);
        frost.setBackgroundColor(0x0FFFFFFF);
        root.addView(frost, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        GradientDrawable sh = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xA6000000, 0x73000000, 0x73000000, 0xB3000000});
        sh.setDither(true);
        shade.setBackground(sh);
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        TextView title = text(22, "sans-serif-light", Color.WHITE);
        title.setText("Apps ouvertes");
        col.addView(title, llp(-2, -2, dp(56), dp(40), 0, 0));

        ramText = text(10, "sans-serif-medium", 0xB3FFFFFF);
        ramText.setLetterSpacing(0.12f);
        col.addView(ramText, llp(-2, -2, dp(56), dp(10), 0, dp(8)));
        FrameLayout track = new FrameLayout(this);
        GradientDrawable tr = new GradientDrawable();
        tr.setCornerRadius(dp(2));
        tr.setColor(0x33FFFFFF);
        track.setBackground(tr);
        ramFill = new View(this);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(2));
        fill.setColor(0xD9FFFFFF);
        ramFill.setBackground(fill);
        track.addView(ramFill, new FrameLayout.LayoutParams(0, -1));
        col.addView(track, llp(dp(220), dp(4), dp(56), 0, 0, 0));

        FrameLayout middle = new FrameLayout(this);
        middle.setClipChildren(false);
        col.addView(middle, new LinearLayout.LayoutParams(-1, 0, 1f));

        scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        int side = (getResources().getDisplayMetrics().widthPixels - dp(280)) / 2;
        scroll.setPadding(side, 0, side, 0);
        strip = new LinearLayout(this);
        strip.setClipChildren(false);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(strip, new FrameLayout.LayoutParams(-2, -1));
        middle.addView(scroll, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));

        empty = text(15, "sans-serif-light", 0xCCFFFFFF);
        empty.setText("Aucune app ouverte");
        empty.setFocusable(true);
        middle.addView(empty, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.setOrientation(LinearLayout.VERTICAL);
        closeAll = text(12, "sans-serif-medium", Color.WHITE);
        closeAll.setText("✕  Tout fermer");
        closeAll.setGravity(Gravity.CENTER);
        closeAll.setPadding(dp(18), 0, dp(18), 0);
        closeAll.setFocusable(true);
        closeAll.setId(View.generateViewId());
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(16));
        pill.setColor(0x33FFFFFF);
        closeAll.setBackground(pill);
        closeAll.setOnFocusChangeListener((v, has) -> {
            pill.setColor(has ? 0xF2FFFFFF : 0x33FFFFFF);
            closeAll.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        closeAll.setOnClickListener(v -> closeAll());
        bottom.addView(closeAll, new LinearLayout.LayoutParams(-2, dp(32)));
        TextView hint = text(10, "sans-serif-medium", 0x80FFFFFF);
        hint.setText("▲  fermer l'app      OK  ouvrir      ◀ ▶  parcourir      RETOUR  quitter");
        hint.setLetterSpacing(0.06f);
        bottom.addView(hint, llp(-2, -2, 0, dp(14), 0, 0));
        col.addView(bottom, llp(-1, -2, 0, 0, 0, dp(36)));
        return root;
    }

    private View card(Card c) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout tile = new FrameLayout(this) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w) * 9 / 16, MeasureSpec.EXACTLY));
            }
        };
        tile.setId(View.generateViewId());
        tile.setTag(c);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16)); }
        });
        tile.setBackgroundColor(0xFF1C1C1E);
        ImageView img = new ImageView(this);
        img.setImageDrawable(c.art);
        img.setScaleType(c.isBanner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (!c.isBanner) img.setPadding(dp(40), dp(30), dp(40), dp(30));
        tile.addView(img, new FrameLayout.LayoutParams(-1, -1));
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(16));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        tile.setForeground(ring);
        tile.getForeground().setAlpha(0);
        box.addView(tile, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(13, "sans-serif-medium", Color.WHITE);
        label.setText(c.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0.6f);
        box.addView(label, llp(-1, -2, 0, dp(16), 0, 0));

        tile.setOnClickListener(v -> {
            try { startActivity(c.launch); } catch (RuntimeException ignored) { }
            finish();
        });
        tile.setOnFocusChangeListener((v, has) -> {
            v.getForeground().setAlpha(has ? 255 : 0);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? dp(16) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.6f) : new DecelerateInterpolator())
                    .setDuration(has ? 300 : 200).start();
            label.animate().alpha(has ? 1f : 0.6f).setDuration(200).start();
            if (has) scroll.post(() -> scroll.smoothScrollTo(box.getLeft() + box.getWidth() / 2
                    - (scroll.getWidth() - scroll.getPaddingLeft() - scroll.getPaddingRight()) / 2, 0));
        });
        cards.add(tile);
        return box;
    }

    private void wireFocus() {
        for (int i = 0; i < cards.size(); i++) {
            View c = cards.get(i);
            c.setNextFocusLeftId(cards.get(Math.max(0, i - 1)).getId());
            c.setNextFocusRightId(cards.get(Math.min(cards.size() - 1, i + 1)).getId());
            c.setNextFocusDownId(closeAll.getId());
        }
        closeAll.setNextFocusUpId(cards.isEmpty() ? closeAll.getId() : cards.get(0).getId());
        closeAll.setNextFocusLeftId(closeAll.getId());
        closeAll.setNextFocusRightId(closeAll.getId());
        closeAll.setNextFocusDownId(closeAll.getId());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        View f = getCurrentFocus();
        int k = e.getKeyCode();
        if ((k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_MENU) && f != null && cards.contains(f)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) close(f);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- helpers

    /** Small copy of the wallpaper with a real (box) blur, so it reads as frosted glass once stretched. */
    private Bitmap tiny(String path) {
        try (java.io.InputStream in = path.startsWith("asset:") ? getAssets().open(path.substring(6))
                : new java.io.FileInputStream(path)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 8;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) return null;
            int w = 192, h = Math.max(1, w * b.getHeight() / b.getWidth());
            Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
            int[] px = new int[w * h];
            s.getPixels(px, 0, w, 0, 0, w, h);
            for (int pass = 0; pass < 3; pass++) { boxBlur(px, w, h, 5, true); boxBlur(px, w, h, 5, false); }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, w, 0, 0, w, h);
            return out;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** One horizontal or vertical box-blur pass with a running sum; three passes approximate a Gaussian. */
    private static void boxBlur(int[] px, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int[] line = new int[len];
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) line[i] = px[horizontal ? l * w + i : i * w + l];
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = line[Math.max(0, Math.min(len - 1, i))];
                sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            int n = 2 * r + 1;
            for (int i = 0; i < len; i++) {
                px[horizontal ? l * w + i : i * w + l] = 0xFF000000 | (sr / n) << 16 | (sg / n) << 8 | (sb / n);
                int add = line[Math.min(len - 1, i + r + 1)], sub = line[Math.max(0, i - r)];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
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

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }
}

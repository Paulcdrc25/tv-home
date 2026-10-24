package com.mihir.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Long-press Home anywhere opens the app switcher; a short press still goes home.
 * An accessibility service is the only way a normal app sees Home before the system does.
 * Enabled over ADB by build-and-install.sh.
 */
public class KeyService extends AccessibilityService {

    static final long LONG_PRESS_MS = 450;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean longFired;
    private final Runnable longPress = () -> {
        longFired = true;
        startActivity(new Intent(this, SwitcherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    };

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                longFired = false;
                handler.postDelayed(longPress, LONG_PRESS_MS);
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(longPress);
            // Short press: same as the system's Home (our launcher is the default home).
            if (!longFired) startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        }
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }
}

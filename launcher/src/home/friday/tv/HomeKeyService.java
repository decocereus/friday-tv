package home.friday.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

/** Redirects Home only. Other keys and the firmware's long-press action remain available. */
public class HomeKeyService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver unlock;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (getSystemService(UserManager.class).isUserUnlocked()) probeBoot();
        else {
            unlock = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    unregisterReceiver(this);
                    unlock = null;
                    probeBoot();
                }
            };
            registerReceiver(unlock, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        }
    }

    @Override public boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
            handler.postDelayed(this::openHome, 250);
        }
        return false;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && "com.google.android.apps.tv.launcherx".contentEquals(String.valueOf(event.getPackageName()))
                && String.valueOf(event.getClassName()).endsWith(".HomeActivity")) openHome();
    }

    private void probeBoot() {
        if (SystemClock.elapsedRealtime() > 180_000) return;
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences state = getSharedPreferences("boot-state", MODE_PRIVATE);
        if (boot < 0 || state.getInt("probe", -1) == boot) return;
        state.edit().putInt("probe", boot).apply();
        startActivity(new Intent(this, BootProbeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    private void openHome() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (unlock != null) unregisterReceiver(unlock);
        super.onDestroy();
    }
}

package home.friday.tv;

import android.app.Activity;
import android.os.Bundle;

/** Lets the visible screen emit a window event; it does not force Home over an HDMI input. */
public class BootProbeActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().post(() -> {
            finish();
            overridePendingTransition(0, 0);
        });
    }
}

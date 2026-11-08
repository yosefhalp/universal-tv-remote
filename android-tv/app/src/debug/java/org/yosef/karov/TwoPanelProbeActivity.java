package org.yosef.karov;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

// מסך debug בלבד המשחזר preview ישן ותזוזת שורה בעקבות מיקוד, ללא הגדרות מערכת.
public final class TwoPanelProbeActivity extends Activity {
    static volatile TwoPanelProbeActivity instance;
    static volatile int focusEvents, clicks, wrongClicks;
    private int selected;
    private LinearLayout system;
    private TextView title;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        focusEvents = clicks = wrongClicks = 0;
        selected = 0;
        FrameLayout container = new FrameLayout(this);
        container.setId(R.id.two_panel_fragment_container);
        container.setBackgroundColor(0xff182334);
        FrameLayout list = new FrameLayout(this);
        list.setId(R.id.list);
        container.addView(list, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout old = row("Display & Sound", 0);
        FrameLayout.LayoutParams first = new FrameLayout.LayoutParams(800, 120);
        first.leftMargin = 100;
        first.topMargin = 200;
        list.addView(old, first);
        system = row("System", 1);
        title = (TextView) system.getChildAt(0);
        FrameLayout.LayoutParams second = new FrameLayout.LayoutParams(800, 120);
        second.leftMargin = 100;
        second.topMargin = 750;
        list.addView(system, second);
        setContentView(container);
        old.requestFocus();
        system.setOnFocusChangeListener((view, focused) -> {
            if (!focused) return;
            focusEvents++;
            // עיכוב מכוון: ה-preview והגבולות החדשים מתעדכנים רק אחרי שהפעולה חזרה true.
            main.postDelayed(
                    () -> {
                        selected = 1;
                        FrameLayout.LayoutParams moved =
                                (FrameLayout.LayoutParams) system.getLayoutParams();
                        moved.topMargin = 400;
                        system.setLayoutParams(moved);
                    },
                    100);
        });
    }

    private LinearLayout row(String text, int item) {
        LinearLayout row = new LinearLayout(this);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setBackgroundColor(0xff53677f);
        TextView label = new TextView(this);
        label.setId(android.R.id.title);
        label.setText(text);
        label.setTextColor(0xffffffff);
        label.setTextSize(24);
        row.addView(label, new LinearLayout.LayoutParams(-1, -1));
        row.setOnClickListener(view -> {
            clicks++;
            if (selected != item) wrongClicks++;
        });
        return row;
    }

    void replaceTitle() {
        title.setText("Replacement row");
    }

    void removeFocus() {
        system.clearFocus();
    }

    void focusSystem() {
        system.requestFocus();
    }

    void onePanel() {
        findViewById(R.id.two_panel_fragment_container).setId(View.NO_ID);
    }

    void truncatedTree() {
        // כל הצמתים גלויים באותו חלון; אין container בטווח החיפוש הראשון.
        LinearLayout overflow = new LinearLayout(this);
        overflow.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 600; i++) {
            TextView child = new TextView(this);
            child.setText("Fixture node");
            child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            overflow.addView(child, new LinearLayout.LayoutParams(500, 1));
        }
        setContentView(overflow);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (instance == this) instance = null;
        super.onDestroy();
    }
}

package org.yosef.karov;

import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

// מסך בדיקה נמצא רק ב-APK הפיתוח. הוא מאפשר למדוד לחיצה, גרירה וגלילה בלי אפליקציה חיצונית.
public final class PointerProbeActivity extends Activity {
    private int clicks;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xffe8eaf0);
        root.setPadding(30, 30, 30, 30);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
        Button target = new Button(this);
        target.setText("לחיצות: 0");
        target.setContentDescription("יעד לחיצה");
        target.setOnClickListener(v -> target.setText("לחיצות: " + (++clicks)));
        left.addView(target, new LinearLayout.LayoutParams(-1, 100));
        TextView value = new TextView(this);
        value.setTextColor(0xff111216);
        value.setTextSize(22);
        value.setText("ערך גרירה: 50");
        left.addView(value);
        SeekBar slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgress(50);
        slider.setContentDescription("יעד גרירה");
        left.addView(slider, new LinearLayout.LayoutParams(-1, 120));
        slider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                        value.setText("ערך גרירה: " + progress);
                    }

                    public void onStartTrackingTouch(SeekBar bar) {}

                    public void onStopTrackingTouch(SeekBar bar) {}
                });
        TextView scrolling = new TextView(this);
        scrolling.setTextColor(0xff111216);
        scrolling.setTextSize(22);
        scrolling.setText("גלילה: 0");
        left.addView(scrolling);
        ScrollView scroll = new ScrollView(this);
        scroll.setContentDescription("יעד גלילה");
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -1, 1);
        size.setMargins(35, 0, 0, 0);
        root.addView(scroll, size);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        for (int i = 1; i <= 50; i++) {
            TextView row = new TextView(this);
            row.setText("שורת בדיקה " + i);
            row.setTextSize(24);
            row.setTextColor(0xff111216);
            row.setPadding(20, 24, 20, 24);
            rows.addView(row);
        }
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> scrolling.setText("גלילה: " + y));
        setContentView(root);
    }
}

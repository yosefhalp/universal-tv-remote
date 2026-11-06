package org.yosef.karov;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

// מסך מעבדה בלבד לבדיקת עברית, פוקוס וצילום אמיתי. אינו נכלל בגרסת ההתקנה למשתמש.
public final class InputProbeActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(80, 60, 80, 60);
        panel.setBackgroundColor(0xff111216);
        panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView title = new TextView(this);
        title.setText("בדיקת שליטה אמיתית בטלוויזיה");
        title.setTextColor(0xffff784e);
        title.setTextSize(28);
        panel.addView(title);
        EditText input = new EditText(this);
        input.setHint("חיפוש סרט בעברית");
        input.setSingleLine(true);
        input.setTextColor(0xffffffff);
        input.setHintTextColor(0xffb0b4bd);
        input.setShowSoftInputOnFocus(false);
        panel.addView(input);
        Button action = new Button(this);
        action.setText("בדיקת אישור");
        TextView result = new TextView(this);
        result.setTextColor(0xffffffff);
        action.setOnClickListener(view -> result.setText("הכפתור הופעל מרחוק"));
        panel.addView(action);
        panel.addView(result);
        setContentView(panel);
        input.requestFocus();
    }
}

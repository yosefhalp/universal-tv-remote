package org.yosef.karov;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;

import org.json.*;

public final class MainActivity extends Activity {
    public static volatile boolean visible;
    private Vault vault;
    private LinearLayout content;
    private EditText server, name, enrollment;
    private TextView state;
    private boolean editing;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh =
            new Runnable() {
                @Override
                public void run() {
                    if (state != null)
                        state.setText(
                                SupportService.status
                                        + "\n"
                                        + (TvAccessibilityService.instance == null
                                                ? "נגישות: יש להפעיל בהרשאות המערכת"
                                                : "נגישות: פעילה")
                                        + " • Android "
                                        + Build.VERSION.RELEASE
                                        + " • "
                                        + Build.MODEL);
                    handler.postDelayed(this, 2000);
                }
            };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow()
                .setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE);
        try {
            vault = Vault.open(this);
            render();
            if (vault.enabled() && !vault.get("server").isEmpty()) SupportService.start(this);
        } catch (Exception error) {
            TextView failure = new TextView(this);
            failure.setText("לא ניתן לפתוח את מאגר ההרשאות. אין למחוק נתונים לפני בירור התקלה.");
            setContentView(failure);
        }
        if ("capture".equals(getIntent().getAction())) handler.post(this::requestCapture);
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if ("capture".equals(intent.getAction())) requestCapture();
    }

    @Override
    public void onResume() {
        super.onResume();
        visible = true;
        // חוזרים מהרשאות המערכת עם מצב מעודכן, בלי למחוק שדות שהמתקין עדיין עורך.
        if (vault != null && !editing && !vault.get("server").isEmpty()) render();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override
    public void onPause() {
        visible = false;
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(0xfff3f3f5);
        view.setPadding(0, dp(7), 0, dp(7));
        view.setTextDirection(View.TEXT_DIRECTION_RTL);
        content.addView(view);
        return view;
    }

    private Button button(String label, Runnable action) {
        return button(this, content, label, action);
    }

    private static Button button(
            Activity activity, LinearLayout content, String label, Runnable action) {
        float density = activity.getResources().getDisplayMetrics().density;
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(17);
        button.setPadding(
                Math.round(15 * density),
                Math.round(10 * density),
                Math.round(15 * density),
                Math.round(10 * density));
        android.graphics.drawable.StateListDrawable background =
                new android.graphics.drawable.StateListDrawable();
        background.addState(
                new int[] {android.R.attr.state_focused}, shape(0xffff784e, 0xffffaa8e, density));
        background.addState(
                new int[] {android.R.attr.state_pressed}, shape(0xffff9474, 0xffffaa8e, density));
        background.addState(new int[] {}, shape(0xff24252c, 0xff3c3e47, density));
        button.setBackground(background);
        button.setTextColor(0xfff3f3f5);
        button.setOnFocusChangeListener(
                (v, focused) -> button.setTextColor(focused ? 0xff16171c : 0xfff3f3f5));
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, Math.round(56 * density));
        params.setMargins(0, Math.round(5 * density), 0, Math.round(5 * density));
        content.addView(button, params);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private static android.graphics.drawable.GradientDrawable shape(
            int fill, int stroke, float density) {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(Math.round(10 * density));
        drawable.setStroke(Math.round(density), stroke);
        return drawable;
    }

    private EditText field(String value, String hint, boolean ltr) {
        EditText view = new EditText(this);
        view.setText(value);
        view.setHint(hint);
        view.setSingleLine();
        view.setTextColor(0xfff3f3f5);
        view.setHintTextColor(0xff9a9ca7);
        view.setTextSize(16);
        view.setSelectAllOnFocus(true);
        if (ltr) {
            view.setInputType(
                    android.text.InputType.TYPE_CLASS_TEXT
                            | android.text.InputType.TYPE_TEXT_VARIATION_URI);
            view.setTextDirection(View.TEXT_DIRECTION_LTR);
        }
        content.addView(view, new LinearLayout.LayoutParams(-1, dp(52)));
        return view;
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xff111216);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(48), dp(24), dp(48), dp(30));
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        if (!editing && !vault.get("server").isEmpty()) {
            dashboard();
            return;
        }
        text("קרוב", 36);
        button(
                "עדכוני קרוב",
                () ->
                        startActivity(
                                new Intent(this, org.yosef.karov.updates.UpdateActivity.class)));
        text("עזרה משפחתית בטלוויזיה", 21);
        state = text(SupportService.status, 14);
        storageWarning();
        text(
                "בהכנה הראשונה מזווגים את הטלפון או המחשב של בן המשפחה. לאחר מכן הוא יכול לעזור"
                        + " מרחוק. אפשר להשהות את העזרה ולבטל גישה בכל רגע.",
                15);
        name = field(vault.get("name"), "שם הטלוויזיה", false);
        server = field(vault.get("server"), "https://כתובת-השרת", true);
        enrollment = field("", "סיסמת ההתקנה שלך (בהכנה הראשונה בלבד)", true);
        enrollment.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button(
                "1 · שמירת ההגדרות והפעלת העזרה",
                () -> {
                    try {
                        vault.configure(server.getText().toString(), name.getText().toString());
                        vault.enable(true);
                        if (!enrollment.getText().toString().trim().isEmpty())
                            vault.put("enrollment", enrollment.getText().toString().trim());
                        if (Build.VERSION.SDK_INT >= 33
                                && checkSelfPermission(
                                                android.Manifest.permission.POST_NOTIFICATIONS)
                                        != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            requestPermissions(
                                    new String[] {android.Manifest.permission.POST_NOTIFICATIONS},
                                    3);
                        if (SupportService.instance != null) {
                            stopService(new Intent(this, SupportService.class));
                            handler.postDelayed(() -> SupportService.start(this), 350);
                        } else SupportService.start(this);
                        toast("ההגדרות נשמרו");
                        editing = false;
                        render();
                    } catch (Exception error) {
                        toast(
                                "SERVER_HAS_PAIRINGS".equals(error.getMessage())
                                        ? "לפני שינוי שרת יש לבטל את הזיווגים הקיימים"
                                        : "יש להזין כתובת HTTPS תקינה, ללא נתיב נוסף");
                    }
                });
        button("2 · הפעלת נגישות לשליטה ולצילום", () -> SystemSettings.open(this, true));
        text(
                "בחרו ברשימה ״קרוב — עזרה משפחתית״ ואשרו. אם Android חוסם אפליקציה שהותקנה מקובץ,"
                        + " יש לאפשר אותה תחילה במסך פרטי האפליקציה. ההרשאה מאפשרת צפייה ופעולות רק"
                        + " למכשירים שזיווגתם.",
                13);
        button("3 · זיווג טלפון או מחשב", this::showPairing);
        button("התחלת שיתוף מסך עם אישור", this::requestCapture);
        text(
                Build.VERSION.SDK_INT >= 30
                        ? "בגרסה זו נבדק תחילה צילום דרך נגישות, ללא בקשת שיתוף בכל חיבור. אם היצרן"
                                + " מגביל צילום, אפשר להפעיל שיתוף מסך באישור."
                        : Build.VERSION.SDK_INT == 28
                                ? "אם חלון שיתוף המסך מציע שמירת אישור קבוע, אפשר לבחור בה בהכנה"
                                        + " הראשונה. קרוב תחדש את השיתוף אחרי אתחול רק אם Android"
                                        + " עדיין מאשרת זאת. יש לבדוק גם את השליטה בחצים בדגם הזה."
                                : "בגרסה זו שיתוף מסך רגיל דורש אישור בתחילת השיתוף. מסלול השליטה"
                                        + " הנוספת עשוי לאפשר צילום קבוע ללא אישור חוזר.",
                13);
        text(
                "בגרסאות Android ישנות אפשר להפעיל שליטה נוספת דרך ניפוי שגיאות ברשת. נדרשת הכנה"
                        + " ראשונית באפשרויות המפתחים ואישור קבוע של מפתח קרוב בטלוויזיה.",
                13);
        button(
                "חיבור שליטה נוספת (ADB מקומי)",
                () -> {
                    if (!StoragePolicy.localAdbSetupAllowed(StoragePolicy.location(this))) {
                        storageHelp();
                        return;
                    }
                    try {
                        LocalAdb.get(this).enable(true);
                        toast("הפעילו ניפוי שגיאות ברשת ואשרו את קרוב בטלוויזיה");
                    } catch (Exception error) {
                        toast("לא ניתן להתחיל חיבור");
                    }
                });
        button("בדיקת התאמה למכשיר", this::diagnostics);
        button("מכשירים מורשים וביטול גישה", this::controllers);
        button(
                "השהיית העזרה והפסקת השיתוף",
                () -> {
                    try {
                        vault.enable(false);
                        stopService(new Intent(this, SupportService.class));
                        stopService(new Intent(this, ScreenService.class));
                        toast("העזרה הושהתה");
                    } catch (Exception ignored) {
                        toast("לא ניתן לשמור את השינוי");
                    }
                });
        button(
                "חזרה לצפייה בטלוויזיה",
                () -> {
                    startActivity(
                            new Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_HOME)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                });
        text(
                "השליטה עוברת בחיבור מוצפן. תוכן מוגן עשוי לא להופיע בצילום. כיבוי הסטרימר או ניתוק"
                        + " החשמל מנתקים גם את העזרה.",
                13);
    }

    private void dashboard() {
        text("קרוב  /  מרכז העזרה המשפחתי", 15).setTextColor(0xffff784e);
        text(vault.enabled() ? "עזרה מרחוק מופעלת." : "העזרה מושהית.", 36);
        state = text(SupportService.status, 15);
        storageWarning();
        button(
                "עדכוני קרוב",
                () ->
                        startActivity(
                                new Intent(this, org.yosef.karov.updates.UpdateActivity.class)));
        text(
                "אחרי הזיווג הראשון, בן המשפחה מתחבר מהטלפון או מהמחשב שלו. אפשר להמשיך לצפות"
                        + " כרגיל.",
                17);
        button(
                "חזרה לטלוויזיה",
                () ->
                        startActivity(
                                new Intent(Intent.ACTION_MAIN)
                                        .addCategory(Intent.CATEGORY_HOME)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        button("זיווג טלפון או מחשב חדש", this::showPairing);
        button(
                "בחירת תיקייה לקבצים",
                () -> {
                    if (!TvFileStorage.chooseFolder(this)) {
                        toast(
                                "בורר התיקיות של Android אינו זמין. אפשר להשתמש בתיקיות הזמינות של"
                                        + " קרוב.");
                    }
                });
        if (TvFileStorage.hasFolders(this))
            button(
                    "ביטול גישה לתיקיות שנבחרו",
                    () ->
                            new AlertDialog.Builder(this)
                                    .setTitle("לבטל גישה לתיקיות שנבחרו?")
                                    .setMessage(
                                            "קרוב תפסיק לגשת לתיקיות שבחרתם. הקבצים יישארו במקומם.")
                                    .setPositiveButton(
                                            "ביטול גישה",
                                            (dialog, which) -> {
                                                if (TvFileStorage.forgetFolders(this)) render();
                                                else toast("לא ניתן לבטל את הגישה כרגע.");
                                            })
                                    .setNegativeButton("חזרה", null)
                                    .show());
        if (TvAccessibilityService.instance == null)
            button("השלמת הרשאת הנגישות", () -> SystemSettings.open(this, true));
        button(
                vault.enabled() ? "השהיית העזרה מרחוק" : "הפעלת העזרה מרחוק",
                () -> {
                    try {
                        vault.enable(!vault.enabled());
                        if (vault.enabled()) SupportService.start(this);
                        else {
                            stopService(new Intent(this, SupportService.class));
                            stopService(new Intent(this, ScreenService.class));
                        }
                        render();
                    } catch (Exception error) {
                        toast("לא ניתן לשמור את השינוי");
                    }
                });
        button(
                "הגדרות וחיבור",
                () -> {
                    editing = true;
                    render();
                });
        button("מכשירים מורשים וביטול גישה", this::controllers);
    }

    private void storageWarning() {
        if (!StoragePolicy.EXTERNAL.equals(StoragePolicy.location(this))) return;
        text(
                "קרוב מותקנת בכונן חיצוני. ניתוק הכונן או שינוי מצב USB עלולים להפסיק את העזרה,"
                        + " וגם החיבור לאחר הפעלת הטלוויזיה עלול להתעכב. לפני הכנת שליטה נוספת יש"
                        + " להעביר את קרוב לאחסון הפנימי של הטלוויזיה.",
                15);
        button("העברת קרוב לאחסון פנימי — הנחיות", this::storageHelp);
    }

    // המסך הפרטי מחייב לחיצה מקומית לפני הבורר. חסימת השלט נשארת פעילה גם כשהבורר בחזית.
    public static final class FilesFolderActivity extends Activity {
        private static final int PICK_FOLDER = 901;
        private Button choose;
        private boolean picking;

        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            if (!TvFileStorage.LOCAL_FILES_FOLDER.equals(getIntent().getAction())) {
                finish();
                return;
            }
            TvFileStorage.beginFolderSelection(this);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            picking = state != null && state.getBoolean("picking");
            float density = getResources().getDisplayMetrics().density;
            ScrollView scroll = new ScrollView(this);
            scroll.setBackgroundColor(0xff111216);
            scroll.setFillViewport(true);
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            panel.setPadding(
                    Math.round(48 * density),
                    Math.round(24 * density),
                    Math.round(48 * density),
                    Math.round(30 * density));
            scroll.addView(panel);
            TextView title = new TextView(this);
            title.setText("בחירת תיקייה לקבצים");
            title.setTextSize(32);
            title.setTextColor(0xfff3f3f5);
            panel.addView(title);
            TextView detail = new TextView(this);
            detail.setText(
                    "בחרו באמצעות השלט של הטלוויזיה תיקייה מקומית או תיקייה בכונן USB. "
                            + "קרוב תקבל גישה לקבצים בתיקייה שתבחרו, לפי ההרשאה שתאשרו ב־Android.");
            detail.setTextSize(17);
            detail.setTextColor(0xfff3f3f5);
            panel.addView(detail);
            choose = button(this, panel, "פתיחת בורר התיקיות של Android", this::openPicker);
            choose.setEnabled(!picking);
            button(this, panel, "חזרה", this::finish).requestFocus();
            setContentView(scroll);
        }

        private void openPicker() {
            if (picking) return;
            Intent picker = TvFileStorage.folderIntent(this);
            if (picker == null) {
                message("בורר התיקיות אינו זמין בטלוויזיה הזאת.");
                return;
            }
            try {
                startActivityForResult(picker, PICK_FOLDER);
                picking = true;
                choose.setEnabled(false);
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                message("בורר התיקיות אינו זמין בטלוויזיה הזאת.");
            }
        }

        @Override
        protected void onSaveInstanceState(Bundle state) {
            state.putBoolean("picking", picking);
            super.onSaveInstanceState(state);
        }

        @Override
        protected void onActivityResult(int request, int result, Intent data) {
            super.onActivityResult(request, result, data);
            if (request != PICK_FOLDER) return;
            picking = false;
            choose.setEnabled(true);
            if (result != RESULT_OK) return;
            try {
                TvFileStorage.acceptFolder(this, data);
                message("התיקייה אושרה. אפשר לרענן את יעדי הקבצים בשלט של קרוב.");
                finish();
            } catch (Exception unavailable) {
                message(
                        "לא ניתן לשמור גישה לתיקייה הזאת. בחרו תיקייה אחרת באחסון הטלוויזיה או"
                                + " בכונן USB.");
            }
        }

        private void message(String text) {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        }

        @Override
        protected void onDestroy() {
            // בשינוי תצורה המסך החדש יחליף את הבעלות, בלי לפתוח חלון שבו השלט יכול לאשר.
            if (!isChangingConfigurations()) TvFileStorage.endFolderSelection(this);
            super.onDestroy();
        }
    }

    private void storageHelp() {
        new AlertDialog.Builder(this)
                .setTitle("מעבירים את קרוב לאחסון הטלוויזיה")
                .setMessage(
                        "במסך Android בחרו אחסון בשימוש ← אחסון פנימי משותף ← העברה. אם הקיצור"
                                + " אינו זמין: הגדרות הטלוויזיה ← אפליקציות ← קרוב ← אחסון בשימוש."
                                + "\n\nמשתמשים בפעולת העברה בלבד, ללא הסרת האפליקציה, ניקוי נתונים,"
                                + " שכחת הכונן או פירמוט. השאירו את הכונן מחובר בזמן ההעברה. לאחר"
                                + " סיומה פתחו שוב את קרוב ובדקו שהטלוויזיה מתחברת.")
                .setPositiveButton("פתיחת פרטי קרוב ב-Android", (dialog, which) -> openAppStorage())
                .setNegativeButton("חזרה", null)
                .show();
    }

    private void openAppStorage() {
        try {
            // מסך המערכת מבצע את ההעברה ושומר את נתוני האפליקציה; קרוב אינה מעבירה קבצים בעצמה.
            startActivity(
                    new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            SystemSettings.open(this, false);
            toast("בחרו אפליקציות ← קרוב ← אחסון בשימוש ← אחסון פנימי משותף");
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private void showPairing() {
        if (!vault.enabled() || vault.get("server").isEmpty()) {
            toast("יש לשמור קודם את כתובת השרת ולהפעיל עזרה");
            return;
        }
        try {
            String url = vault.pairing();
            startForegroundService(new Intent(this, SupportService.class).setAction("pair"));
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(20), dp(10), dp(20), dp(10));
            panel.setGravity(Gravity.CENTER);
            ImageView qr = new ImageView(this);
            BitMatrix bits = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 600, 600);
            Bitmap bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < 600; y++)
                for (int x = 0; x < 600; x++)
                    bitmap.setPixel(x, y, bits.get(x, y) ? Color.BLACK : Color.WHITE);
            qr.setImageBitmap(bitmap);
            qr.setContentDescription("קוד זיווג לסריקה בטלפון");
            panel.addView(qr, new LinearLayout.LayoutParams(dp(190), dp(190)));
            TextView help = new TextView(this);
            help.setText(
                    "סורקים בטלפון ולוחצים על חיבור ושמירה.\n"
                            + "או פותחים את הכתובת הבאה ומקלידים את הקוד:");
            help.setTextSize(16);
            help.setGravity(Gravity.CENTER);
            panel.addView(help);
            TextView link = new TextView(this);
            link.setText(vault.get("server"));
            link.setTextSize(18);
            link.setTextDirection(View.TEXT_DIRECTION_LTR);
            link.setTextIsSelectable(true);
            panel.addView(link);
            TextView code = new TextView(this);
            code.setTextSize(28);
            code.setGravity(Gravity.CENTER);
            code.setTextDirection(View.TEXT_DIRECTION_LTR);
            code.setTypeface(android.graphics.Typeface.MONOSPACE);
            panel.addView(code);
            TextView expiry = new TextView(this);
            expiry.setText("למכשיר אחד, לעשר דקות. לאחר הזיווג לא צריך להזין קוד שוב.");
            expiry.setTextSize(14);
            expiry.setGravity(Gravity.CENTER);
            panel.addView(expiry);
            AlertDialog dialog =
                    new AlertDialog.Builder(this)
                            .setTitle("זיווג מכשיר חדש")
                            .setView(panel)
                            .setPositiveButton("סגירה", null)
                            .show();
            Handler updates = new Handler(Looper.getMainLooper());
            Runnable refresh =
                    new Runnable() {
                        @Override
                        public void run() {
                            SupportService service = SupportService.instance;
                            String label =
                                    service == null
                                            ? "ממתינים לחיבור לשרת…"
                                            : service.pairingLabel();
                            // אין לעורר אירועי נגישות שוב ושוב כשלא השתנה דבר במסך הזיווג.
                            if (!android.text.TextUtils.equals(code.getText(), label)) {
                                code.setText(label);
                                code.setTextSize(
                                        label.matches("[0-9]{2}-[0-9]{3}-[0-9]{3}") ? 28 : 14);
                            }
                            if (dialog.isShowing()) updates.postDelayed(this, 500);
                        }
                    };
            dialog.setOnDismissListener(d -> {
                updates.removeCallbacks(refresh);
                qr.setImageDrawable(null);
                bitmap.recycle();
            });
            refresh.run();
        } catch (Exception error) {
            toast("לא ניתן ליצור זיווג כרגע");
        }
    }

    private void requestCapture() {
        if (!vault.enabled()) {
            toast("יש להפעיל קודם את העזרה");
            return;
        }
        CaptureConsentActivity.request(this, false);
    }

    private void diagnostics() {
        String report =
                "דגם: "
                        + Build.MANUFACTURER
                        + " "
                        + Build.MODEL
                        + "\nAndroid: "
                        + Build.VERSION.RELEASE
                        + " (API "
                        + Build.VERSION.SDK_INT
                        + ")\nנגישות: "
                        + (TvAccessibilityService.instance != null ? "פעילה" : "כבויה")
                        + "\nצילום דרך נגישות: "
                        + (Build.VERSION.SDK_INT >= 30
                                ? "זמין לפי גרסת מערכת, דורש בדיקה"
                                : "לא זמין")
                        + "\nחצים מובנים דרך נגישות: "
                        + (Build.VERSION.SDK_INT >= 33
                                ? "זמינים לפי גרסת מערכת"
                                : "ניווט באמצעות רכיבי המסך")
                        + "\nחיבור: "
                        + SupportService.status;
        new AlertDialog.Builder(this)
                .setTitle("בדיקת התאמה")
                .setMessage(report)
                .setPositiveButton("סגירה", null)
                .show();
    }

    private void controllers() {
        try {
            JSONArray list = vault.controllers();
            String[] names = new String[list.length()];
            for (int i = 0; i < names.length; i++)
                names[i] = list.getJSONObject(i).getString("name");
            AlertDialog.Builder dialog =
                    new AlertDialog.Builder(this)
                            .setTitle(
                                    names.length == 0
                                            ? "אין מכשירים מורשים"
                                            : "בחרו מכשיר לביטול גישה")
                            .setNegativeButton("סגירה", null);
            dialog.setItems(
                    names,
                    (d, index) -> {
                        try {
                            String id = list.getJSONObject(index).getString("id");
                            new AlertDialog.Builder(this)
                                    .setTitle("לבטל את הגישה של " + names[index] + "?")
                                    .setPositiveButton(
                                            "ביטול גישה",
                                            (confirm, which) -> {
                                                try {
                                                    vault.revoke(id);
                                                    if (SupportService.instance != null)
                                                        SupportService.instance
                                                                .disconnectController();
                                                    toast("הגישה בוטלה");
                                                } catch (Exception ignored) {
                                                    toast("לא ניתן לשמור");
                                                }
                                            })
                                    .setNegativeButton("חזרה", null)
                                    .show();
                        } catch (Exception ignored) {
                        }
                    });
            dialog.show();
        } catch (Exception error) {
            toast("לא ניתן לקרוא את רשימת המכשירים");
        }
    }
}

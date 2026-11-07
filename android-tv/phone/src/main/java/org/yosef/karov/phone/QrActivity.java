package org.yosef.karov.phone;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.*;

import java.util.Collections;

public final class QrActivity extends ComponentActivity {
    private BarcodeView camera;
    private boolean visible;
    private final ActivityResultLauncher<String> permission =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted && visible) camera.resume();
                        else if (!granted) {
                            Toast.makeText(
                                            this,
                                            "אפשר להתחבר גם בקוד קצר, בלי מצלמה",
                                            Toast.LENGTH_LONG)
                                    .show();
                            finish();
                        }
                    });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 21, 25));
        TextView title = new TextView(this);
        title.setText("סורקים את הקוד בטלוויזיה");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(20, 28, 20, 24);
        root.addView(title);
        camera = new BarcodeView(this);
        camera.setUseTextureView(false);
        // מציגים את כל פריים המצלמה, גם כשהטלפון לרוחב, כדי שלא לחתוך קוד בקצה התמונה.
        camera.setPreviewScalingStrategy(
                new com.journeyapps.barcodescanner.camera.FitCenterStrategy());
        camera.setDecoderFactory(
                new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        camera.addStateListener(
                new CameraPreview.StateListener() {
                    @Override
                    public void previewSized() {}

                    @Override
                    public void previewStarted() {}

                    @Override
                    public void previewStopped() {}

                    @Override
                    public void cameraClosed() {}

                    @Override
                    public void cameraError(Exception error) {
                        Toast.makeText(
                                        QrActivity.this,
                                        "המצלמה אינה זמינה. אפשר להתחבר בקוד קצר",
                                        Toast.LENGTH_LONG)
                                .show();
                        finish();
                    }
                });
        root.addView(camera, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView help = new TextView(this);
        help.setText("מכוונים את המצלמה ל-QR במסך הזיווג של קרוב");
        help.setTextColor(Color.WHITE);
        help.setTextSize(16);
        help.setPadding(24, 24, 24, 16);
        help.setGravity(Gravity.CENTER);
        root.addView(help);
        Button cancel = new Button(this);
        cancel.setText("חזרה לקוד קצר");
        cancel.setOnClickListener(v -> finish());
        root.addView(cancel);
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(
                root,
                (view, insets) -> {
                    Insets safe =
                            insets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                                            | WindowInsetsCompat.Type.displayCutout());
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                    return insets;
                });
        camera.decodeSingle(
                new BarcodeCallback() {
                    @Override
                    public void barcodeResult(BarcodeResult result) {
                        // התוכן מוחזר פנימית לאפליקציה ונבדק בה; אין פתיחת קישור מתוך הסורק.
                        setResult(RESULT_OK, new Intent().putExtra("qr", result.getText()));
                        finish();
                    }
                });
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.CAMERA);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            camera.resume();
    }

    @Override
    protected void onPause() {
        visible = false;
        camera.pause();
        super.onPause();
    }
}

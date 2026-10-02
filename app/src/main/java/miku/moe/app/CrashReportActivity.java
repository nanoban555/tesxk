package miku.moe.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class CrashReportActivity extends Activity {
    private String crashLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        crashLog = CrashReportStore.read(this);
        showCrashDialog();
    }

    private void showCrashDialog() {
        TextView textView = new TextView(this);
        int padding = dp(20);
        textView.setPadding(padding, padding, padding, padding);
        textView.setText(crashLog);
        textView.setTextIsSelectable(true);
        textView.setTextSize(12f);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(textView);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Aplikasi mengalami masalah")
                .setMessage("Aplikasi berhenti karena terjadi error. Salin log ini untuk diperiksa.")
                .setView(scrollView)
                .setPositiveButton("Salin log", (dialog, which) -> copyLog())
                .setNegativeButton("Tutup", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void copyLog() {
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("Crash Log", crashLog));
            Toast.makeText(this, "Log disalin", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

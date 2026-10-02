package miku.moe.app;

import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashHandler implements Thread.UncaughtExceptionHandler {
    private final Application application;
    private CrashHandler(Application application) {
        this.application = application;
    }

    public static void install(Application application) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(application));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        String log = buildLog(thread, throwable);
        CrashReportStore.save(application, log);
        Log.e("CrashHandler", log);
        try {
            Intent intent = new Intent(application, CrashReportActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            application.startActivity(intent);
        } catch (Throwable ignored) {
        }
        try {
            Thread.sleep(700);
        } catch (InterruptedException ignored) {
        }
        Process.killProcess(Process.myPid());
        System.exit(10);
    }

    private String getAppInfo() {
        try {
            PackageInfo packageInfo = application.getPackageManager().getPackageInfo(application.getPackageName(), 0);
            long versionCode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = packageInfo.getLongVersionCode();
            } else {
                versionCode = packageInfo.versionCode;
            }
            return application.getPackageName() + " " + packageInfo.versionName + " (" + versionCode + ")";
        } catch (Throwable throwable) {
            return application.getPackageName();
        }
    }

    private String buildLog(Thread thread, Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        throwable.printStackTrace(printWriter);
        printWriter.flush();
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        return "Waktu: " + time + "\n"
                + "Thread: " + thread.getName() + "\n"
                + "App: " + getAppInfo() + "\n"
                + "Android: " + Build.VERSION.RELEASE + " SDK " + Build.VERSION.SDK_INT + "\n"
                + "Perangkat: " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n"
                + stringWriter;
    }
}

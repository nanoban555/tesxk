package miku.moe.app;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class CrashReportStore {
    private static final String FILE_NAME = "last_crash_log.txt";

    private CrashReportStore() {}

    public static void save(Context context, String log) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            FileOutputStream outputStream = new FileOutputStream(file, false);
            outputStream.write(log.getBytes(StandardCharsets.UTF_8));
            outputStream.close();
        } catch (Throwable ignored) {
        }
    }

    public static String read(Context context) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.exists()) return "Log kerusakan tidak ditemukan.";
            byte[] data = new byte[(int) file.length()];
            java.io.FileInputStream inputStream = new java.io.FileInputStream(file);
            int read = 0;
            while (read < data.length) {
                int chunk = inputStream.read(data, read, data.length - read);
                if (chunk <= 0) break;
                read += chunk;
            }
            inputStream.close();
            if (read <= 0) return "Log kerusakan kosong.";
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "Gagal membaca log kerusakan: " + e.getMessage();
        }
    }
}

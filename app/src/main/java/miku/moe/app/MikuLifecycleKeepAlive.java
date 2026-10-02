package miku.moe.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class MikuLifecycleKeepAlive implements Application.ActivityLifecycleCallbacks {
    private final Set<FragmentManager> registeredManagers = Collections.newSetFromMap(new WeakHashMap<>());
    private final FragmentManager.FragmentLifecycleCallbacks fragmentCallbacks = new FragmentManager.FragmentLifecycleCallbacks() {
        @Override
        public void onFragmentViewCreated(FragmentManager fm, Fragment fragment, View view, Bundle savedInstanceState) {
            keepFragmentAlive(fragment, view);
        }

        @Override
        public void onFragmentResumed(FragmentManager fm, Fragment fragment) {
            keepFragmentAlive(fragment, fragment.getView());
        }
    };

    public static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new MikuLifecycleKeepAlive());
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        keepActivityAlive(activity);
        registerFragmentLifecycle(activity);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        keepActivityAlive(activity);
        registerFragmentLifecycle(activity);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        keepActivityAlive(activity);
        registerFragmentLifecycle(activity);
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        Window window = activity.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            View decorView = window.getDecorView();
            if (decorView != null) decorView.setKeepScreenOn(false);
        }
    }

    private void registerFragmentLifecycle(Activity activity) {
        if (!(activity instanceof FragmentActivity)) return;
        FragmentManager fragmentManager = ((FragmentActivity) activity).getSupportFragmentManager();
        if (registeredManagers.contains(fragmentManager)) return;
        fragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true);
        registeredManagers.add(fragmentManager);
    }

    private static void keepActivityAlive(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View decorView = window.getDecorView();
        if (decorView != null) decorView.setKeepScreenOn(true);
    }

    private static void keepFragmentAlive(Fragment fragment, View view) {
        if (view != null) view.setKeepScreenOn(true);
        Activity activity = fragment.getActivity();
        if (activity != null) keepActivityAlive(activity);
    }
}

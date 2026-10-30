package com.github.catvod;

import android.app.Activity;
import android.content.Context;

import java.lang.ref.WeakReference;

/**
 * SHELL_JAR_SETTINGS_REDO: provide activityForDialog/setActivity for feimao jar TV dialogs.
 * Rollback: restore upstream Init (only set/context).
 */
public class Init {

    private WeakReference<Context> context;
    private WeakReference<Activity> activity;

    private static Init get() {
        return Loader.INSTANCE;
    }

    public static void set(Context context) {
        get().context = new WeakReference<>(context);
    }

    public static Context context() {
        WeakReference<Context> ref = get().context;
        return ref == null ? null : ref.get();
    }

    /** Used by feimao/PanSettings jar AlertDialogs on TV. */
    public static void setActivity(Activity activity) {
        get().activity = activity == null ? null : new WeakReference<>(activity);
        if (activity != null) {
            get().context = new WeakReference<>(activity);
        }
    }

    public static Activity activityForDialog() {
        WeakReference<Activity> ref = get().activity;
        Activity a = ref == null ? null : ref.get();
        if (a != null && !a.isFinishing()) return a;
        Context c = context();
        if (c instanceof Activity) {
            Activity act = (Activity) c;
            if (!act.isFinishing()) return act;
        }
        return null;
    }

    /** Alias some jars may call. */
    public static Activity activity() {
        return activityForDialog();
    }

    private static class Loader {
        static volatile Init INSTANCE = new Init();
    }
}

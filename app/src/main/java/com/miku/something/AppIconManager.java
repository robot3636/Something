package com.miku.something;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Switches the launcher icon and app name by enabling one activity-alias
 * and disabling the other one.
 *
 * The aliases are declared in AndroidManifest.xml (".AliasDefault" and ".AliasAlt").
 */
public class AppIconManager {

    // Alias class names relative to the app namespace, exactly as declared in the Manifest
    private static final String ALIAS_DEFAULT_NAME = ".AliasDefault";
    private static final String ALIAS_ALT_NAME     = ".AliasAlt";

    /**
     * Builds the fully-qualified alias name from the package of the running app,
     * so nothing is hardcoded if the package name changes later.
     * Note: this assumes the namespace and the applicationId are the same.
     */
    private static String fullName(Context context, String relativeName) {
        return context.getPackageName() + relativeName;
    }

    /**
     * Enables the target alias and disables all the others.
     *
     * Note: the launcher may take a few seconds to reflect the change.
     *
     * @param context      application or activity context
     * @param targetAlias  the fully-qualified alias name to activate
     */
    public static void switchTo(Context context, String targetAlias) {
        PackageManager pm = context.getPackageManager();

        // All registered aliases - add more here if you define more in the Manifest
        String[] allAliases = {
                fullName(context, ALIAS_DEFAULT_NAME),
                fullName(context, ALIAS_ALT_NAME)
        };

        for (String alias : allAliases) {
            int state = alias.equals(targetAlias)
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED   // activate this one
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED; // deactivate the rest

            pm.setComponentEnabledSetting(
                    new ComponentName(context, alias),
                    state,
                    PackageManager.DONT_KILL_APP // do not force-kill the app on change
            );
        }
    }

    // ---- Convenience methods ------------------------------------------

    /** Restores the normal launcher icon and name. */
    public static void useDefault(Context context) {
        switchTo(context, fullName(context, ALIAS_DEFAULT_NAME));
    }

    /** Applies the alternative launcher icon and name. */
    public static void useAlt(Context context) {
        switchTo(context, fullName(context, ALIAS_ALT_NAME));
    }
}

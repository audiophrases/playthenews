package com.eugen.playthenews;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

/** Saved language is independent of the phone language; new installs start in Catalan. */
public class AppLanguage extends Application {
    private static Context app;
    @Override public void onCreate() { super.onCreate(); app = this; }
    static String code(Context c) { return c.getSharedPreferences("appearance", 0).getString("language", "ca"); }
    static String code() { return code(app); }
    static String text(int id) {
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(code()));
        return app.createConfigurationContext(config).getString(id);
    }
}

package com.eugen.playthenews;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import java.util.Locale;

/** Shared bilingual UI, enlarged typography and persistent day/night preference. */
public abstract class AppActivity extends Activity {
    static boolean day(Context c) { return c.getSharedPreferences("appearance", 0).getBoolean("day", false); }
    @Override protected void attachBaseContext(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(AppLanguage.code(base)));
        config.fontScale *= 1.25f;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (day(base) ? Configuration.UI_MODE_NIGHT_NO : Configuration.UI_MODE_NIGHT_YES);
        super.attachBaseContext(base.createConfigurationContext(config));
    }
    @Override protected void onCreate(Bundle state) {
        setTheme(day(this) ? R.style.PlayerDayTheme : R.style.PlayerTheme);
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(day(this)
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
    }
    protected int color(int res) { return getResources().getColor(res); }
}

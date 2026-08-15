package com.cloudflared.tunnel.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.cloudflared.tunnel.model.Config;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class ConfigManager {

    private static final String PREFS_NAME = "cloudflared_configs";
    private static final String KEY_CONFIGS = "configs";
    private static final String KEY_DEFAULT = "default_config_id";

    private final SharedPreferences prefs;
    private final Gson gson;

    public ConfigManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    public List<Config> getAllConfigs() {
        String json = prefs.getString(KEY_CONFIGS, "");
        if (json.isEmpty()) return new ArrayList<>();
        try {
            Type type = new TypeToken<List<Config>>() {}.getType();
            List<Config> list = gson.fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public Config getConfig(String id) {
        for (Config c : getAllConfigs()) {
            if (c.getId().equals(id)) return c;
        }
        return null;
    }

    public void saveConfig(Config config) {
        List<Config> list = getAllConfigs();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(config.getId())) {
                list.set(i, config);
                found = true;
                break;
            }
        }
        if (!found) list.add(config);
        if (config.isDefault()) {
            for (Config c : list) {
                if (!c.getId().equals(config.getId())) c.setDefault(false);
            }
            prefs.edit().putString(KEY_DEFAULT, config.getId()).apply();
        }
        prefs.edit().putString(KEY_CONFIGS, gson.toJson(list)).apply();
    }

    public void deleteConfig(String id) {
        List<Config> list = getAllConfigs();
        list.removeIf(c -> c.getId().equals(id));
        if (id.equals(prefs.getString(KEY_DEFAULT, ""))) {
            prefs.edit().remove(KEY_DEFAULT).apply();
            if (!list.isEmpty()) {
                list.get(0).setDefault(true);
                prefs.edit().putString(KEY_DEFAULT, list.get(0).getId()).apply();
            }
        }
        prefs.edit().putString(KEY_CONFIGS, gson.toJson(list)).apply();
    }

    public Config getDefaultConfig() {
        String defaultId = prefs.getString(KEY_DEFAULT, "");
        if (defaultId.isEmpty()) {
            List<Config> list = getAllConfigs();
            return list.isEmpty() ? null : list.get(0);
        }
        return getConfig(defaultId);
    }

    public void setDefaultConfig(String id) {
        List<Config> list = getAllConfigs();
        for (Config c : list) {
            c.setDefault(c.getId().equals(id));
        }
        prefs.edit().putString(KEY_CONFIGS, gson.toJson(list))
                .putString(KEY_DEFAULT, id).apply();
    }
}

package com.wm.streamhub;

import android.app.Application;

import com.wm.streamhub.model.ServerProfile;
import com.wm.streamhub.net.NetworkMonitor;
import com.wm.streamhub.util.BuildDefaults;
import com.wm.streamhub.util.Prefs;

import java.util.List;

public class StreamHubApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        NetworkMonitor.get(this);
        seedPresetServer();
    }

    /** First launch only: if the build was configured with a preset line, install it. */
    private void seedPresetServer() {
        Prefs prefs = new Prefs(this);
        List<ServerProfile> existing = prefs.getServers();
        if (!existing.isEmpty()) return;

        boolean hasXtream = !BuildDefaults.PRESET_HOST.isEmpty();
        boolean hasM3u = !BuildDefaults.PRESET_M3U.isEmpty();
        if (!hasXtream && !hasM3u) return;

        ServerProfile s = new ServerProfile();
        s.name = BuildDefaults.PRESET_NAME.isEmpty() ? "Main server" : BuildDefaults.PRESET_NAME;
        if (hasXtream) {
            s.type = ServerProfile.TYPE_XTREAM;
            s.host = BuildDefaults.PRESET_HOST;
            s.username = BuildDefaults.PRESET_USER;
            s.password = BuildDefaults.PRESET_PASS;
        } else {
            s.type = ServerProfile.TYPE_M3U;
            s.m3uUrl = BuildDefaults.PRESET_M3U;
        }
        prefs.upsertServer(s);
        prefs.setActiveServerId(s.id);
    }
}

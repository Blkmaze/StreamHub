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

    /** First launch only: if the build was configured with preset lines, install them. */
    private void seedPresetServer() {
        Prefs prefs = new Prefs(this);
        List<ServerProfile> existing = prefs.getServers();
        if (!existing.isEmpty()) return;

        String firstId = null;
        int i = 0;
        for (String[] row : BuildDefaults.PRESET_SERVERS) {
            if (row.length < 2 || row[1] == null || row[1].trim().isEmpty()) continue;
            ServerProfile s = new ServerProfile();
            // Timestamp alone can collide when several presets are created in the
            // same millisecond; suffix with the loop index to keep ids unique.
            s.id = System.currentTimeMillis() + "-" + (i++);
            s.name = row[0];
            s.type = ServerProfile.TYPE_XTREAM;
            s.host = row[1];
            s.username = "";
            s.password = "";
            prefs.upsertServer(s);
            if (firstId == null) firstId = s.id;
        }

        boolean hasM3u = !BuildDefaults.PRESET_M3U.isEmpty();
        if (firstId == null && hasM3u) {
            ServerProfile s = new ServerProfile();
            s.name = BuildDefaults.PRESET_NAME.isEmpty() ? "Main server" : BuildDefaults.PRESET_NAME;
            s.type = ServerProfile.TYPE_M3U;
            s.m3uUrl = BuildDefaults.PRESET_M3U;
            prefs.upsertServer(s);
            firstId = s.id;
        }

        if (firstId != null) prefs.setActiveServerId(firstId);
    }
}

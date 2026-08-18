package com.wm.streamhub.ui;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.RecyclerView;

import com.wm.streamhub.data.ContentRepository;
import com.wm.streamhub.R;
import com.wm.streamhub.net.AdaptiveEngine;
import com.wm.streamhub.net.NetworkMonitor;
import com.wm.streamhub.ui.adapter.RowAdapter;
import com.wm.streamhub.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/** Everything tunable, with a plain-English explanation next to each item. */
@UnstableApi
public class SettingsActivity extends AppCompatActivity {

    private Prefs prefs;
    private AdaptiveEngine engine;
    private NetworkMonitor monitor;
    private RowAdapter adapter;
    private RecyclerView list;
    private TextView title, body;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = new Prefs(this);
        engine = new AdaptiveEngine(this);
        monitor = NetworkMonitor.get(this);

        list = findViewById(R.id.settingsList);
        title = findViewById(R.id.settingTitle);
        body = findViewById(R.id.settingBody);
        adapter = RowAdapter.attach(list);

        adapter.setOnFocus(new RowAdapter.OnFocus() {
            @Override
            public void onFocus(int position, RowAdapter.Row row) {
                describe(String.valueOf(row.tag), row.title);
            }
        });

        adapter.setOnClick(new RowAdapter.OnClick() {
            @Override
            public void onClick(int position, RowAdapter.Row row) {
                handle(String.valueOf(row.tag));
                build();
                RowAdapter.focusPosition(list, position);
            }
        });

        build();
        RowAdapter.focusPosition(list, 0);
    }

    private void build() {
        List<RowAdapter.Row> rows = new ArrayList<>();

        rows.add(new RowAdapter.Row("Adapt to internet speed",
                prefs.isAdaptiveEnabled()
                        ? "On · " + engine.statusLine()
                        : "Off · always request maximum quality")
                .badge(prefs.isAdaptiveEnabled() ? "ON" : "OFF").tag("adaptive"));

        String profile;
        switch (prefs.getBufferProfile()) {
            case Prefs.PROFILE_ANTI_BUFFER: profile = "Anti-buffer"; break;
            case Prefs.PROFILE_BALANCED: profile = "Balanced"; break;
            case Prefs.PROFILE_LOW_LATENCY: profile = "Low latency"; break;
            default: profile = "Auto (now: " + engine.profileName() + ")"; break;
        }
        rows.add(new RowAdapter.Row("Buffering mode", profile).tag("profile"));

        int cap = prefs.getMaxBitrateKbps();
        rows.add(new RowAdapter.Row("Quality ceiling",
                cap == 0 ? "Automatic" : cap + " kbps").tag("cap"));

        rows.add(new RowAdapter.Row("Automatic server failover",
                prefs.isAutoFailover()
                        ? "On · switch to a backup server when a channel dies"
                        : "Off")
                .badge(prefs.isAutoFailover() ? "ON" : "OFF").tag("failover"));

        rows.add(new RowAdapter.Row("On-screen diagnostics",
                prefs.isShowStats() ? "Shown during playback" : "Hidden")
                .badge(prefs.isShowStats() ? "ON" : "OFF").tag("stats"));

        rows.add(new RowAdapter.Row("Run a speed test",
                monitor.connectionLabel() + " · " + monitor.speedLabel()).tag("speedtest"));

        rows.add(new RowAdapter.Row("Your name for support",
                prefs.getClientName().isEmpty() ? "Not set" : prefs.getClientName())
                .tag("clientname"));

        rows.add(new RowAdapter.Row("Support server URL",
                prefs.getChatBaseUrl().isEmpty() ? "Not configured" : prefs.getChatBaseUrl())
                .tag("chaturl"));

        rows.add(new RowAdapter.Row("Support server key",
                prefs.getChatApiKey().isEmpty() ? "Not configured" : "Configured").tag("chatkey"));

        rows.add(new RowAdapter.Row("WhatsApp number",
                prefs.getSupportWhatsApp().isEmpty() ? "Not set" : prefs.getSupportWhatsApp())
                .tag("wa"));
        rows.add(new RowAdapter.Row("Telegram handle",
                prefs.getSupportTelegram().isEmpty() ? "Not set" : prefs.getSupportTelegram())
                .tag("tg"));
        rows.add(new RowAdapter.Row("Support email",
                prefs.getSupportEmail().isEmpty() ? "Not set" : prefs.getSupportEmail())
                .tag("mail"));

        rows.add(new RowAdapter.Row("Clear cached channel lists",
                "Forces a fresh download from every server").tag("clear"));

        rows.add(new RowAdapter.Row("About this device",
                "ID " + prefs.getDeviceId() + " · StreamHub TV 1.0.0").tag("about"));

        adapter.submit(rows);
    }

    private void handle(String tag) {
        if ("adaptive".equals(tag)) {
            prefs.setAdaptiveEnabled(!prefs.isAdaptiveEnabled());
        } else if ("profile".equals(tag)) {
            prefs.setBufferProfile((prefs.getBufferProfile() + 1) % 4);
        } else if ("cap".equals(tag)) {
            int[] caps = {0, 8000, 4500, 2500, 1200};
            int cur = prefs.getMaxBitrateKbps();
            int next = 0;
            for (int i = 0; i < caps.length; i++) {
                if (caps[i] == cur) {
                    next = caps[(i + 1) % caps.length];
                    break;
                }
            }
            prefs.setMaxBitrateKbps(next);
        } else if ("failover".equals(tag)) {
            prefs.setAutoFailover(!prefs.isAutoFailover());
        } else if ("stats".equals(tag)) {
            prefs.setShowStats(!prefs.isShowStats());
        } else if ("speedtest".equals(tag)) {
            runSpeedTest();
        } else if ("clientname".equals(tag)) {
            edit("Your name", prefs.getClientName(), InputType.TYPE_CLASS_TEXT, value -> {
                prefs.setClientName(value);
                build();
            });
        } else if ("chaturl".equals(tag)) {
            edit("Support server URL (https://xxx.supabase.co)", prefs.getChatBaseUrl(),
                    InputType.TYPE_TEXT_VARIATION_URI, value -> {
                        prefs.setChatBaseUrl(value);
                        build();
                    });
        } else if ("chatkey".equals(tag)) {
            edit("Support server key (anon key)", prefs.getChatApiKey(),
                    InputType.TYPE_CLASS_TEXT, value -> {
                        prefs.setChatApiKey(value);
                        build();
                    });
        } else if ("wa".equals(tag)) {
            edit("WhatsApp number with country code", prefs.getSupportWhatsApp(),
                    InputType.TYPE_CLASS_PHONE, value -> {
                        prefs.setSupportWhatsApp(value);
                        build();
                    });
        } else if ("tg".equals(tag)) {
            edit("Telegram handle", prefs.getSupportTelegram(),
                    InputType.TYPE_CLASS_TEXT, value -> {
                        prefs.setSupportTelegram(value);
                        build();
                    });
        } else if ("mail".equals(tag)) {
            edit("Support email", prefs.getSupportEmail(),
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, value -> {
                        prefs.setSupportEmail(value);
                        build();
                    });
        } else if ("clear".equals(tag)) {
            ContentRepository.get(this).clearCaches();
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show();
        }
    }

    private void runSpeedTest() {
        List<com.wm.streamhub.model.ServerProfile> servers = prefs.getServers();
        if (servers.isEmpty()) {
            Toast.makeText(this, "Add a server first", Toast.LENGTH_SHORT).show();
            return;
        }
        com.wm.streamhub.model.ServerProfile s = servers.get(0);
        String url = s.isXtream()
                ? new com.wm.streamhub.data.XtreamClient(s).probeUrl()
                : s.m3uUrl;
        body.setText("Measuring throughput against " + s.label() + "…");
        monitor.probe(url);
        list.postDelayed(new Runnable() {
            @Override
            public void run() {
                build();
                body.setText("Result: " + monitor.speedLabel()
                        + " (" + NetworkMonitor.tierName(monitor.tier()) + ")\n\n"
                        + engine.statusLine());
            }
        }, 4000);
    }

    private interface OnValue {
        void apply(String value);
    }

    private void edit(String label, String current, int inputType, final OnValue cb) {
        final EditText et = new EditText(this);
        et.setText(current);
        et.setInputType(InputType.TYPE_CLASS_TEXT | inputType);
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setView(et)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> cb.apply(et.getText().toString().trim()))
                .show();
    }

    private void describe(String tag, String rowTitle) {
        title.setText(rowTitle);
        String text;
        if ("adaptive".equals(tag)) {
            text = "The app measures how much bandwidth you actually have and keeps the "
                    + "stream inside that budget.\n\n"
                    + "It uses 72% of measured throughput as the ceiling, drops the ceiling "
                    + "by 30% after every stall, and lifts it back up after 45 seconds of "
                    + "clean playback.\n\n"
                    + "Turn it off only if you have a fast, rock-solid line and want the "
                    + "highest bitrate at all times.";
        } else if ("profile".equals(tag)) {
            text = "How much video is held in memory ahead of what you are watching.\n\n"
                    + "Auto — picks for you from the measured speed.\n"
                    + "Anti-buffer — 45–180 s buffer. Best for slow or shared connections; "
                    + "channels take a moment longer to start but survive dips.\n"
                    + "Balanced — 20–90 s. The default for a healthy line.\n"
                    + "Low latency — 8–30 s. Live sport feels closer to real time, but a "
                    + "weak line will stall.";
        } else if ("cap".equals(tag)) {
            text = "A hard limit on video bitrate, regardless of what the server offers.\n\n"
                    + "Useful on metered connections, or when several TVs share one line. "
                    + "Automatic lets the adaptive engine decide.";
        } else if ("failover".equals(tag)) {
            text = "When a channel fails, the app looks for the same channel name on your "
                    + "other servers (ignoring HD/FHD/VIP tags) and switches to it.\n\n"
                    + "Servers are tried in the order set on the Servers screen.";
        } else if ("stats".equals(tag)) {
            text = "Shows a live overlay during playback: measured speed, current bitrate "
                    + "ceiling, resolution, codec, buffer depth and stall count.\n\n"
                    + "You can also toggle it with ◀ on the remote while watching.";
        } else if ("speedtest".equals(tag)) {
            text = "Downloads a short sample from your own provider — not from a generic "
                    + "speed-test site — so the number reflects the path that actually "
                    + "matters for streaming.";
        } else if ("chaturl".equals(tag) || "chatkey".equals(tag)) {
            text = "Connects the Support screen to your Supabase project so customers can "
                    + "message you from the couch and you can reply from the admin console.\n\n"
                    + "Run supabase/schema.sql once, then paste the project URL and the anon "
                    + "key here. The key is safe to ship: row-level security limits each "
                    + "device to its own conversation.";
        } else if ("clientname".equals(tag)) {
            text = "Shown next to this device in your support console, so you know who is "
                    + "writing without asking.";
        } else if ("wa".equals(tag) || "tg".equals(tag) || "mail".equals(tag)) {
            text = "Fallback contact routes shown on the Support screen. Any that are left "
                    + "blank are hidden from the customer.";
        } else if ("clear".equals(tag)) {
            text = "Channel lists are cached in memory so moving between columns is instant. "
                    + "Clear the cache after your provider adds or removes channels.";
        } else {
            text = "Device ID " + prefs.getDeviceId() + "\n"
                    + "StreamHub TV 1.0.0\n"
                    + "Connection: " + monitor.connectionLabel() + "\n"
                    + "Measured: " + monitor.speedLabel() + "\n"
                    + "Servers: " + prefs.getServers().size();
        }
        body.setText(text);
    }
}

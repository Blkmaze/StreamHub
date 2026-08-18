package com.wm.streamhub.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.wm.streamhub.model.ChatMessage;
import com.wm.streamhub.util.Http;
import com.wm.streamhub.util.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Two-way messaging between the customer's device and whoever is running support.
 *
 * Backed by Supabase (PostgREST over HTTPS) so there is no server to babysit:
 *  - the app POSTs a row into `messages`
 *  - support replies from the bundled admin console (admin/console.html)
 *  - the app polls for new rows every few seconds while the chat screen is open
 *
 * If no backend is configured the screen still works as a local outbox plus
 * one-tap WhatsApp / Telegram / email handoff.
 */
public class ChatClient {

    public interface Listener {
        void onMessages(List<ChatMessage> messages);

        void onError(String message);
    }

    private static final int POLL_MS = 5000;

    private final Context ctx;
    private final Prefs prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Listener listener;
    private boolean polling = false;
    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            refresh();
            main.postDelayed(this, POLL_MS);
        }
    };

    public ChatClient(Context c) {
        this.ctx = c.getApplicationContext();
        this.prefs = new Prefs(this.ctx);
    }

    public boolean isConfigured() {
        return !prefs.getChatBaseUrl().isEmpty() && !prefs.getChatApiKey().isEmpty();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void startPolling() {
        if (!isConfigured()) return;
        polling = true;
        main.removeCallbacks(pollTask);
        main.post(pollTask);
    }

    public void stopPolling() {
        polling = false;
        main.removeCallbacks(pollTask);
    }

    // ------------------------------------------------------------------

    public List<ChatMessage> cached() {
        List<ChatMessage> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getChatCacheJson());
            for (int i = 0; i < arr.length(); i++) {
                out.add(ChatMessage.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        sort(out);
        return out;
    }

    private void cache(List<ChatMessage> msgs) {
        JSONArray arr = new JSONArray();
        int start = Math.max(0, msgs.size() - 200);
        for (int i = start; i < msgs.size(); i++) {
            try {
                arr.put(msgs.get(i).toJson());
            } catch (Exception ignored) {
            }
        }
        prefs.setChatCacheJson(arr.toString());
    }

    public void send(final String body) {
        final String text = body == null ? "" : body.trim();
        if (text.isEmpty()) return;

        final ChatMessage local = new ChatMessage();
        local.id = "local-" + System.currentTimeMillis();
        local.deviceId = prefs.getDeviceId();
        local.sender = "client";
        local.body = text;
        local.pending = true;

        List<ChatMessage> msgs = cached();
        msgs.add(local);
        cache(msgs);
        emit(msgs);

        if (!isConfigured()) {
            local.pending = false;
            local.failed = true;
            List<ChatMessage> m2 = cached();
            markLocal(m2, local.id, false, true);
            cache(m2);
            emit(m2);
            error("No support server configured. Use WhatsApp / Telegram / email below,"
                    + " or add the backend URL in Settings.");
            return;
        }

        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("device_id", prefs.getDeviceId());
                    payload.put("sender", "client");
                    payload.put("body", text);
                    String name = prefs.getClientName();
                    payload.put("client_name", name.isEmpty() ? "Device " + prefs.getDeviceId() : name);
                    payload.put("app_version", "1.0.0");

                    Http.post(restUrl("messages"), "application/json",
                            payload.toString(), authHeaders(true), 10000, 15000);

                    List<ChatMessage> m = cached();
                    markLocal(m, local.id, false, false);
                    cache(m);
                    refresh();
                } catch (Exception e) {
                    List<ChatMessage> m = cached();
                    markLocal(m, local.id, false, true);
                    cache(m);
                    emit(m);
                    error("Could not reach support: " + Http.trim(e.getMessage(), 120));
                }
            }
        });
    }

    public void refresh() {
        if (!isConfigured()) {
            emit(cached());
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = restUrl("messages")
                            + "?device_id=eq." + Http.urlEncode(prefs.getDeviceId())
                            + "&select=id,device_id,sender,body,created_at"
                            + "&order=created_at.asc&limit=200";
                    String body = Http.getWithHeaders(url, authHeaders(false), 10000, 15000);
                    JSONArray arr = new JSONArray(body);

                    List<ChatMessage> remote = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        ChatMessage m = new ChatMessage();
                        m.id = o.optString("id", String.valueOf(i));
                        m.deviceId = o.optString("device_id", "");
                        m.sender = o.optString("sender", "support");
                        m.body = o.optString("body", "");
                        m.createdAt = parseTime(o.optString("created_at", ""));
                        remote.add(m);
                    }
                    // Keep any local rows that have not made it to the server yet.
                    for (ChatMessage c : cached()) {
                        if (c.id.startsWith("local-") && (c.pending || c.failed)) remote.add(c);
                    }
                    sort(remote);
                    cache(remote);
                    emit(remote);
                } catch (Exception e) {
                    emit(cached());
                    error("Offline: " + Http.trim(e.getMessage(), 100));
                }
            }
        });
    }

    /** Optional broadcast banner: latest row in the `notices` table. */
    public void fetchNotice(final com.wm.streamhub.data.ContentRepository.Callback<String> cb) {
        if (!isConfigured()) {
            cb.onResult("");
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = restUrl("notices")
                            + "?select=body,created_at&order=created_at.desc&limit=1";
                    String body = Http.getWithHeaders(url, authHeaders(false), 8000, 10000);
                    JSONArray arr = new JSONArray(body);
                    final String text = arr.length() > 0
                            ? arr.getJSONObject(0).optString("body", "") : "";
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onResult(text);
                        }
                    });
                } catch (Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onResult("");
                        }
                    });
                }
            }
        });
    }

    // ------------------------------------------------------------------

    private String restUrl(String table) {
        String base = prefs.getChatBaseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/rest/v1/" + table;
    }

    private String[] authHeaders(boolean write) {
        String key = prefs.getChatApiKey();
        if (write) {
            return new String[]{
                    "apikey", key,
                    "Authorization", "Bearer " + key,
                    "x-device-id", prefs.getDeviceId(),
                    "Prefer", "return=minimal"
            };
        }
        return new String[]{
                "apikey", key,
                "Authorization", "Bearer " + key,
                "x-device-id", prefs.getDeviceId(),
                "Accept", "application/json"
        };
    }

    private static void markLocal(List<ChatMessage> list, String id, boolean pending, boolean failed) {
        for (ChatMessage m : list) {
            if (m.id.equals(id)) {
                m.pending = pending;
                m.failed = failed;
            }
        }
    }

    private static void sort(List<ChatMessage> list) {
        Collections.sort(list, new Comparator<ChatMessage>() {
            @Override
            public int compare(ChatMessage a, ChatMessage b) {
                return Long.compare(a.createdAt, b.createdAt);
            }
        });
    }

    private static long parseTime(String iso) {
        if (iso == null || iso.isEmpty()) return System.currentTimeMillis();
        try {
            String s = iso.replace("Z", "+0000");
            int dot = s.indexOf('.');
            if (dot > 0) {
                int plus = s.indexOf('+', dot);
                if (plus > 0) s = s.substring(0, dot) + s.substring(plus);
                else s = s.substring(0, dot);
            }
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US);
            return f.parse(s.length() <= 19 ? s + "+0000" : s).getTime();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private void emit(final List<ChatMessage> msgs) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onMessages(msgs);
            }
        });
    }

    private void error(final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onError(msg);
            }
        });
    }
}

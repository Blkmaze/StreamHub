package com.wm.streamhub.data;

import com.wm.streamhub.model.Category;
import com.wm.streamhub.model.ServerProfile;
import com.wm.streamhub.model.StreamItem;
import com.wm.streamhub.util.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Xtream Codes / XUI panel API. All methods are blocking - call from a worker thread. */
public class XtreamClient {

    public static class AccountInfo {
        public boolean authorized;
        public String status = "";
        public String expiry = "";
        public int maxConnections;
        public int activeConnections;
        public String message = "";
    }

    private final ServerProfile server;
    private final int connectMs;
    private final int readMs;

    public XtreamClient(ServerProfile server) {
        this(server, 12000, 20000);
    }

    public XtreamClient(ServerProfile server, int connectMs, int readMs) {
        this.server = server;
        this.connectMs = connectMs;
        this.readMs = readMs;
    }

    private String api(String action, String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append(server.normalizedHost()).append("/player_api.php?username=")
                .append(Http.urlEncode(server.username))
                .append("&password=").append(Http.urlEncode(server.password));
        if (action != null && !action.isEmpty()) sb.append("&action=").append(action);
        if (extra != null && !extra.isEmpty()) sb.append(extra);
        return sb.toString();
    }

    public AccountInfo authenticate() throws IOException {
        String body = Http.get(api("", null), connectMs, readMs, server.userAgent);
        AccountInfo info = new AccountInfo();
        try {
            JSONObject root = new JSONObject(body);
            JSONObject u = root.optJSONObject("user_info");
            if (u == null) {
                info.message = "Panel did not return user_info";
                return info;
            }
            info.authorized = u.optInt("auth", 0) == 1;
            info.status = u.optString("status", "");
            info.maxConnections = parseInt(u.optString("max_connections", "0"));
            info.activeConnections = parseInt(u.optString("active_cons", "0"));
            long exp = parseLong(u.optString("exp_date", "0"));
            if (exp > 0) {
                info.expiry = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(new java.util.Date(exp * 1000L));
            } else {
                info.expiry = "Unlimited";
            }
            if (!info.authorized) info.message = "Login rejected by the panel";
            else if (!"Active".equalsIgnoreCase(info.status)) {
                info.message = "Account status: " + info.status;
            }
        } catch (Exception e) {
            info.message = "Unexpected response: " + Http.trim(body, 120);
        }
        return info;
    }

    public List<Category> categories(int kind) throws IOException {
        String action = kind == StreamItem.KIND_VOD ? "get_vod_categories"
                : kind == StreamItem.KIND_SERIES ? "get_series_categories"
                : "get_live_categories";
        String body = Http.get(api(action, null), connectMs, readMs, server.userAgent);
        List<Category> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Category(o.optString("category_id", String.valueOf(i)),
                        o.optString("category_name", "Unnamed")));
            }
        } catch (Exception e) {
            throw new IOException("Could not read categories: " + Http.trim(body, 120));
        }
        return out;
    }

    public List<StreamItem> streams(int kind, String categoryId) throws IOException {
        String action = kind == StreamItem.KIND_VOD ? "get_vod_streams"
                : kind == StreamItem.KIND_SERIES ? "get_series"
                : "get_live_streams";
        String extra = (categoryId == null || categoryId.isEmpty())
                ? "" : "&category_id=" + Http.urlEncode(categoryId);
        String body = Http.get(api(action, extra), connectMs, readMs, server.userAgent);
        List<StreamItem> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                StreamItem s = new StreamItem();
                s.kind = kind;
                s.serverId = server.id;
                s.serverName = server.label();
                s.categoryId = o.optString("category_id", categoryId == null ? "" : categoryId);
                s.name = o.optString("name", o.optString("title", "Unnamed"));
                s.logo = o.optString("stream_icon", o.optString("cover", ""));
                s.epgChannelId = o.optString("epg_channel_id", "");
                s.number = o.optInt("num", i + 1);
                s.containerExtension = o.optString("container_extension", "");
                if (kind == StreamItem.KIND_SERIES) {
                    s.id = o.optString("series_id", "");
                } else {
                    s.id = o.optString("stream_id", "");
                }
                if (!s.id.isEmpty()) out.add(s);
            }
        } catch (Exception e) {
            throw new IOException("Could not read channel list: " + Http.trim(body, 120));
        }
        return out;
    }

    /** Flattens a series into playable episodes. */
    public List<StreamItem> episodes(String seriesId, String seriesName) throws IOException {
        String body = Http.get(api("get_series_info", "&series_id=" + Http.urlEncode(seriesId)),
                connectMs, readMs, server.userAgent);
        List<StreamItem> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(body);
            JSONObject eps = root.optJSONObject("episodes");
            if (eps == null) return out;
            java.util.Iterator<String> seasons = eps.keys();
            while (seasons.hasNext()) {
                String season = seasons.next();
                JSONArray arr = eps.optJSONArray(season);
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    StreamItem s = new StreamItem();
                    s.kind = StreamItem.KIND_SERIES;
                    s.serverId = server.id;
                    s.serverName = server.label();
                    s.id = o.optString("id", "");
                    s.containerExtension = o.optString("container_extension", "mp4");
                    String title = o.optString("title", "Episode " + o.optString("episode_num", ""));
                    s.name = "S" + season + " · " + title;
                    s.categoryName = seriesName;
                    if (!s.id.isEmpty()) out.add(s);
                }
            }
        } catch (Exception e) {
            throw new IOException("Could not read episodes: " + Http.trim(body, 120));
        }
        return out;
    }

    /** Builds the playback URL. hls=true asks the panel for its adaptive .m3u8 output. */
    public String streamUrl(StreamItem item, boolean hls) {
        String base = server.normalizedHost() + "/";
        String creds = Http.urlEncode(server.username) + "/" + Http.urlEncode(server.password) + "/";
        if (item.kind == StreamItem.KIND_VOD) {
            String ext = item.containerExtension.isEmpty() ? "mp4" : item.containerExtension;
            return base + "movie/" + creds + item.id + "." + ext;
        }
        if (item.kind == StreamItem.KIND_SERIES) {
            String ext = item.containerExtension.isEmpty() ? "mp4" : item.containerExtension;
            return base + "series/" + creds + item.id + "." + ext;
        }
        return base + "live/" + creds + item.id + (hls ? ".m3u8" : ".ts");
    }

    /** A small file we can range-download to measure real throughput to this panel. */
    public String probeUrl() {
        return server.normalizedHost() + "/player_api.php?username="
                + Http.urlEncode(server.username) + "&password="
                + Http.urlEncode(server.password) + "&action=get_live_streams";
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }
}

package com.wm.streamhub.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.wm.streamhub.model.Category;
import com.wm.streamhub.model.ServerProfile;
import com.wm.streamhub.model.StreamItem;
import com.wm.streamhub.util.Prefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One place that knows how to turn a ServerProfile into categories and channels,
 * whichever protocol it speaks. Results are cached in memory per server so moving
 * between columns is instant.
 */
public class ContentRepository {

    public interface Callback<T> {
        void onResult(T value);

        void onError(String message);
    }

    private static ContentRepository instance;

    private final Context ctx;
    private final Prefs prefs;
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    /** serverId -> all entries (M3U playlists are parsed once and reused). */
    private final Map<String, List<StreamItem>> playlistCache = new HashMap<>();
    /** serverId+kind+categoryId -> streams (Xtream). */
    private final Map<String, List<StreamItem>> streamCache = new HashMap<>();
    private final Map<String, List<Category>> categoryCache = new HashMap<>();

    private ContentRepository(Context c) {
        this.ctx = c.getApplicationContext();
        this.prefs = new Prefs(this.ctx);
    }

    public static synchronized ContentRepository get(Context c) {
        if (instance == null) instance = new ContentRepository(c);
        return instance;
    }

    public void clearCaches() {
        playlistCache.clear();
        streamCache.clear();
        categoryCache.clear();
        prefs.clearCache();
    }

    public void clearServer(String serverId) {
        playlistCache.remove(serverId);
        List<String> kill = new ArrayList<>();
        for (String k : streamCache.keySet()) if (k.startsWith(serverId + "|")) kill.add(k);
        for (String k : kill) streamCache.remove(k);
        kill.clear();
        for (String k : categoryCache.keySet()) if (k.startsWith(serverId + "|")) kill.add(k);
        for (String k : kill) categoryCache.remove(k);
    }

    // ------------------------------------------------------------------

    public void loadCategories(final ServerProfile server, final int kind,
                               final Callback<List<Category>> cb) {
        final String key = server.id + "|" + kind;
        List<Category> cached = categoryCache.get(key);
        if (cached != null) {
            cb.onResult(cached);
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Category> result;
                    if (server.isXtream()) {
                        result = new XtreamClient(server).categories(kind);
                    } else {
                        result = categoriesFromPlaylist(loadPlaylist(server), kind);
                    }
                    sortCategories(result);
                    categoryCache.put(key, result);
                    post(cb, result);
                } catch (final Exception e) {
                    postError(cb, friendly(e));
                }
            }
        });
    }

    public void loadStreams(final ServerProfile server, final int kind, final String categoryId,
                            final Callback<List<StreamItem>> cb) {
        final String key = server.id + "|" + kind + "|" + categoryId;
        List<StreamItem> cached = streamCache.get(key);
        if (cached != null) {
            cb.onResult(cached);
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<StreamItem> result;
                    if (server.isXtream()) {
                        result = new XtreamClient(server).streams(kind, categoryId);
                        for (StreamItem s : result) {
                            s.categoryName = categoryNameFor(server, kind, categoryId);
                        }
                    } else {
                        result = new ArrayList<>();
                        for (StreamItem s : loadPlaylist(server)) {
                            if (s.kind == kind
                                    && (categoryId == null || categoryId.isEmpty()
                                    || categoryId.equals(s.categoryId))) {
                                result.add(s);
                            }
                        }
                    }
                    streamCache.put(key, result);
                    post(cb, result);
                } catch (final Exception e) {
                    postError(cb, friendly(e));
                }
            }
        });
    }

    public void loadEpisodes(final ServerProfile server, final StreamItem series,
                             final Callback<List<StreamItem>> cb) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!server.isXtream()) {
                        post(cb, Collections.singletonList(series));
                        return;
                    }
                    List<StreamItem> eps = new XtreamClient(server).episodes(series.id, series.name);
                    post(cb, eps);
                } catch (Exception e) {
                    postError(cb, friendly(e));
                }
            }
        });
    }

    /** Runs a login check and reports a human-readable result. */
    public void testServer(final ServerProfile server, final Callback<String> cb) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (server.isXtream()) {
                        XtreamClient.AccountInfo info = new XtreamClient(server, 9000, 12000)
                                .authenticate();
                        if (!info.authorized) {
                            postError(cb, info.message.isEmpty() ? "Login failed" : info.message);
                            return;
                        }
                        post(cb, "Connected · " + info.status
                                + " · expires " + info.expiry
                                + " · " + info.activeConnections + "/" + info.maxConnections
                                + " connections in use");
                    } else {
                        List<StreamItem> items = M3UParser.parse(server);
                        playlistCache.put(server.id, items);
                        post(cb, "Playlist loaded · " + items.size() + " entries");
                    }
                } catch (Exception e) {
                    postError(cb, friendly(e));
                }
            }
        });
    }

    /** Searches every enabled server for entries matching the query. */
    public void search(final List<ServerProfile> servers, final int kind, final String query,
                       final Callback<List<StreamItem>> cb) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                String q = query.toLowerCase(Locale.US).trim();
                List<StreamItem> hits = new ArrayList<>();
                for (ServerProfile s : servers) {
                    if (!s.enabled) continue;
                    try {
                        if (s.isXtream()) {
                            for (StreamItem it : new XtreamClient(s).streams(kind, null)) {
                                if (it.name.toLowerCase(Locale.US).contains(q)) hits.add(it);
                                if (hits.size() > 400) break;
                            }
                        } else {
                            for (StreamItem it : loadPlaylist(s)) {
                                if (it.kind == kind && it.name.toLowerCase(Locale.US).contains(q)) {
                                    hits.add(it);
                                }
                                if (hits.size() > 400) break;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                post(cb, hits);
            }
        });
    }

    /**
     * Failover: given a channel that just died, find the same channel name on the
     * other enabled servers, best server first.
     */
    public void findAlternatives(final List<ServerProfile> servers, final StreamItem item,
                                 final Callback<List<StreamItem>> cb) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                List<StreamItem> alts = new ArrayList<>();
                String target = normalize(item.name);
                for (ServerProfile s : servers) {
                    if (!s.enabled || s.id.equals(item.serverId)) continue;
                    try {
                        List<StreamItem> pool;
                        if (s.isXtream()) {
                            pool = new XtreamClient(s, 8000, 12000).streams(item.kind, null);
                        } else {
                            pool = loadPlaylist(s);
                        }
                        for (StreamItem cand : pool) {
                            if (cand.kind == item.kind && normalize(cand.name).equals(target)) {
                                alts.add(cand);
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                post(cb, alts);
            }
        });
    }

    // ------------------------------------------------------------------

    private List<StreamItem> loadPlaylist(ServerProfile server) throws Exception {
        List<StreamItem> cached = playlistCache.get(server.id);
        if (cached != null) return cached;
        List<StreamItem> parsed = M3UParser.parse(server);
        playlistCache.put(server.id, parsed);
        return parsed;
    }

    private List<Category> categoriesFromPlaylist(List<StreamItem> items, int kind) {
        Map<String, Category> map = new LinkedHashMap<>();
        for (StreamItem s : items) {
            if (s.kind != kind) continue;
            Category c = map.get(s.categoryId);
            if (c == null) {
                c = new Category(s.categoryId, s.categoryName);
                map.put(s.categoryId, c);
            }
            c.count++;
        }
        return new ArrayList<>(map.values());
    }

    private String categoryNameFor(ServerProfile server, int kind, String categoryId) {
        List<Category> cats = categoryCache.get(server.id + "|" + kind);
        if (cats == null) return "";
        for (Category c : cats) if (c.id.equals(categoryId)) return c.name;
        return "";
    }

    private static void sortCategories(List<Category> cats) {
        Collections.sort(cats, new Comparator<Category>() {
            @Override
            public int compare(Category a, Category b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String out = s.toLowerCase(Locale.US);
        out = out.replaceAll("\\[[^\\]]*\\]", " ");
        out = out.replaceAll("\\([^)]*\\)", " ");
        out = out.replaceAll("(?i)\\b(fhd|uhd|hd|sd|4k|hevc|h265|h264|raw|backup|vip|us|usa)\\b", " ");
        out = out.replaceAll("[^a-z0-9]+", "");
        return out;
    }

    private static String friendly(Exception e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        String low = m.toLowerCase(Locale.US);
        if (low.contains("timeout") || low.contains("timed out")) {
            return "The server did not answer in time. It may be busy or blocked by your network.";
        }
        if (low.contains("unable to resolve host") || low.contains("unknownhost")) {
            return "Cannot find that host. Check the address for typos.";
        }
        if (low.contains("econnrefused") || low.contains("failed to connect")) {
            return "Connection refused. Check the port number.";
        }
        if (low.contains("http 401") || low.contains("http 403")) {
            return "The panel rejected these credentials.";
        }
        if (low.contains("http 5")) {
            return "The panel returned a server error. Try again shortly.";
        }
        return m;
    }

    private <T> void post(final Callback<T> cb, final T value) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onResult(value);
            }
        });
    }

    private <T> void postError(final Callback<T> cb, final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(msg);
            }
        });
    }
}

package com.wm.streamhub.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.RecyclerView;

import com.wm.streamhub.R;
import com.wm.streamhub.chat.ChatClient;
import com.wm.streamhub.data.ContentRepository;
import com.wm.streamhub.data.NowPlaying;
import com.wm.streamhub.model.Category;
import com.wm.streamhub.model.ServerProfile;
import com.wm.streamhub.model.StreamItem;
import com.wm.streamhub.net.NetworkMonitor;
import com.wm.streamhub.player.PlayerActivity;
import com.wm.streamhub.ui.adapter.RowAdapter;
import com.wm.streamhub.util.Prefs;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Servers → Categories → Channels, three columns, driven entirely by the D-pad.
 */
@UnstableApi
public class MainActivity extends AppCompatActivity {

    private static final int SECTION_LIVE = 0;
    private static final int SECTION_MOVIES = 1;
    private static final int SECTION_SERIES = 2;
    private static final int SECTION_FAVORITES = 3;

    private Prefs prefs;
    private ContentRepository repo;
    private NetworkMonitor monitor;
    private ChatClient chat;

    private RecyclerView rvServers, rvCategories, rvChannels;
    private RowAdapter adServers, adCategories, adChannels;
    private LinearLayout navRail;
    private TextView sectionTitle, netStatus, clock, emptyState, noticeBar,
            hdrChannels, hdrCategories;
    private ProgressBar progress;

    private final List<ServerProfile> servers = new ArrayList<>();
    private final List<StreamItem> currentItems = new ArrayList<>();
    private ServerProfile activeServer;
    private String activeCategoryId = "";
    private String activeCategoryName = "";
    private int section = SECTION_LIVE;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateStatusBar();
            ticker.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        repo = ContentRepository.get(this);
        monitor = NetworkMonitor.get(this);
        chat = new ChatClient(this);

        navRail = findViewById(R.id.navRail);
        sectionTitle = findViewById(R.id.sectionTitle);
        netStatus = findViewById(R.id.netStatus);
        clock = findViewById(R.id.clock);
        emptyState = findViewById(R.id.emptyState);
        noticeBar = findViewById(R.id.noticeBar);
        progress = findViewById(R.id.progress);
        hdrChannels = findViewById(R.id.hdrChannels);
        hdrCategories = findViewById(R.id.hdrCategories);

        rvServers = findViewById(R.id.listServers);
        rvCategories = findViewById(R.id.listCategories);
        rvChannels = findViewById(R.id.listChannels);

        adServers = RowAdapter.attach(rvServers);
        adCategories = RowAdapter.attach(rvCategories);
        adChannels = RowAdapter.attach(rvChannels);

        buildNavRail();
        wireLists();
        loadServers();
        fetchNotice();
        chat.registerDevice(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ticker.removeCallbacks(tick);
        ticker.post(tick);
        // Server list may have changed in the manage screen.
        if (serversChanged()) loadServers();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(tick);
        monitor.persist();
    }

    // ------------------------------------------------------------------
    // Navigation rail
    // ------------------------------------------------------------------

    private void buildNavRail() {
        navRail.removeAllViews();
        addNavItem("▶", getString(R.string.tab_live), new Runnable() {
            @Override
            public void run() {
                switchSection(SECTION_LIVE);
            }
        });
        addNavItem("▣", getString(R.string.tab_movies), new Runnable() {
            @Override
            public void run() {
                switchSection(SECTION_MOVIES);
            }
        });
        addNavItem("≡", getString(R.string.tab_series), new Runnable() {
            @Override
            public void run() {
                switchSection(SECTION_SERIES);
            }
        });
        addNavItem("★", getString(R.string.tab_favorites), new Runnable() {
            @Override
            public void run() {
                switchSection(SECTION_FAVORITES);
            }
        });
        addNavItem("✎", getString(R.string.tab_servers), new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(MainActivity.this, ServersActivity.class));
            }
        });
        addNavItem("✆", getString(R.string.tab_support), new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(MainActivity.this, ChatActivity.class));
            }
        });
        addNavItem("⚙", getString(R.string.tab_settings), new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
    }

    private void addNavItem(String icon, String label, final Runnable action) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_row, navRail, false);
        ((TextView) v.findViewById(R.id.rowTitle)).setText(label);
        TextView ic = v.findViewById(R.id.rowIcon);
        ic.setVisibility(View.VISIBLE);
        ic.setText(icon);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                action.run();
            }
        });
        navRail.addView(v);
    }

    private void markNav() {
        for (int i = 0; i < navRail.getChildCount(); i++) {
            navRail.getChildAt(i).setActivated(i == section);
        }
    }

    private void switchSection(int newSection) {
        section = newSection;
        markNav();
        String title;
        switch (section) {
            case SECTION_MOVIES: title = getString(R.string.tab_movies); break;
            case SECTION_SERIES: title = getString(R.string.tab_series); break;
            case SECTION_FAVORITES: title = getString(R.string.tab_favorites); break;
            default: title = getString(R.string.tab_live); break;
        }
        sectionTitle.setText(title);

        if (section == SECTION_FAVORITES) {
            showFavorites();
        } else if (activeServer != null) {
            loadCategories(activeServer);
        }
    }

    private int kind() {
        switch (section) {
            case SECTION_MOVIES: return StreamItem.KIND_VOD;
            case SECTION_SERIES: return StreamItem.KIND_SERIES;
            default: return StreamItem.KIND_LIVE;
        }
    }

    // ------------------------------------------------------------------
    // Columns
    // ------------------------------------------------------------------

    private void wireLists() {
        adServers.setOnClick(new RowAdapter.OnClick() {
            @Override
            public void onClick(int position, RowAdapter.Row row) {
                if (row.tag instanceof ServerProfile) {
                    adServers.setActivated(position);
                    selectServer((ServerProfile) row.tag);
                    RowAdapter.focusPosition(rvCategories, 0);
                } else {
                    startActivity(new Intent(MainActivity.this, AddServerActivity.class));
                }
            }
        });

        adCategories.setOnClick(new RowAdapter.OnClick() {
            @Override
            public void onClick(int position, RowAdapter.Row row) {
                adCategories.setActivated(position);
                Category c = row.tag instanceof Category ? (Category) row.tag : null;
                activeCategoryId = c == null ? "" : c.id;
                activeCategoryName = c == null ? "All" : c.name;
                loadChannels();
            }
        });

        adChannels.setOnClick(new RowAdapter.OnClick() {
            @Override
            public void onClick(int position, RowAdapter.Row row) {
                if (!(row.tag instanceof StreamItem)) return;
                StreamItem item = (StreamItem) row.tag;
                if (item.kind == StreamItem.KIND_SERIES && item.containerExtension.isEmpty()
                        && activeServer != null && activeServer.isXtream()) {
                    openEpisodes(item);
                    return;
                }
                adChannels.setActivated(position);
                NowPlaying.set(currentItems, position, activeCategoryName);
                startActivity(new Intent(MainActivity.this, PlayerActivity.class));
            }
        });

        adChannels.setOnLongClick(new RowAdapter.OnLongClick() {
            @Override
            public boolean onLongClick(int position, RowAdapter.Row row) {
                if (!(row.tag instanceof StreamItem)) return false;
                StreamItem item = (StreamItem) row.tag;
                boolean added = prefs.toggleFavorite(item);
                toast(added ? "Added to favourites" : "Removed from favourites");
                row.icon = added ? "★" : "";
                adChannels.notifyItemChanged(position);
                return true;
            }
        });
    }

    private boolean serversChanged() {
        List<ServerProfile> latest = prefs.getServers();
        if (latest.size() != servers.size()) return true;
        for (int i = 0; i < latest.size(); i++) {
            ServerProfile a = latest.get(i);
            ServerProfile b = servers.get(i);
            if (!a.id.equals(b.id) || !a.label().equals(b.label()) || a.enabled != b.enabled) {
                return true;
            }
        }
        return false;
    }

    private void loadServers() {
        servers.clear();
        servers.addAll(prefs.getServers());

        List<RowAdapter.Row> rows = new ArrayList<>();
        for (ServerProfile s : servers) {
            RowAdapter.Row r = new RowAdapter.Row(s.label(), s.subtitle()).tag(s);
            if (!s.enabled) r.badge("off");
            rows.add(r);
        }
        rows.add(new RowAdapter.Row("＋  " + getString(R.string.add_server)).tag("add"));
        adServers.submit(rows);

        if (servers.isEmpty()) {
            adCategories.submit(new ArrayList<RowAdapter.Row>());
            adChannels.submit(new ArrayList<RowAdapter.Row>());
            showEmpty(getString(R.string.no_servers));
            RowAdapter.focusPosition(rvServers, 0);
            markNav();
            return;
        }

        ServerProfile chosen = null;
        String activeId = prefs.getActiveServerId();
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).id.equals(activeId)) {
                chosen = servers.get(i);
                adServers.setActivated(i);
            }
        }
        if (chosen == null) {
            chosen = servers.get(0);
            adServers.setActivated(0);
        }
        markNav();
        selectServer(chosen);
    }

    private void selectServer(ServerProfile s) {
        activeServer = s;
        prefs.setActiveServerId(s.id);
        if (s.isXtream()) {
            monitor.probe(new com.wm.streamhub.data.XtreamClient(s).probeUrl());
        } else {
            monitor.probe(s.m3uUrl);
        }
        if (section == SECTION_FAVORITES) {
            showFavorites();
        } else {
            loadCategories(s);
        }
    }

    private void loadCategories(final ServerProfile server) {
        showLoading(true);
        hdrCategories.setText(R.string.col_categories);
        repo.loadCategories(server, kind(), new ContentRepository.Callback<List<Category>>() {
            @Override
            public void onResult(List<Category> value) {
                List<RowAdapter.Row> rows = new ArrayList<>();
                rows.add(new RowAdapter.Row("All", value.size() + " groups").tag(null));
                for (Category c : value) {
                    RowAdapter.Row r = new RowAdapter.Row(c.name).tag(c);
                    if (c.count > 0) r.badge(String.valueOf(c.count));
                    rows.add(r);
                }
                adCategories.submit(rows);
                adCategories.setActivated(0);
                activeCategoryId = "";
                activeCategoryName = "All";
                loadChannels();
            }

            @Override
            public void onError(String message) {
                showLoading(false);
                adCategories.submit(new ArrayList<RowAdapter.Row>());
                adChannels.submit(new ArrayList<RowAdapter.Row>());
                showEmpty(message);
            }
        });
    }

    private void loadChannels() {
        if (activeServer == null) return;
        showLoading(true);
        repo.loadStreams(activeServer, kind(), activeCategoryId,
                new ContentRepository.Callback<List<StreamItem>>() {
                    @Override
                    public void onResult(List<StreamItem> value) {
                        currentItems.clear();
                        currentItems.addAll(value);
                        renderChannels(activeCategoryName);
                    }

                    @Override
                    public void onError(String message) {
                        showLoading(false);
                        adChannels.submit(new ArrayList<RowAdapter.Row>());
                        showEmpty(message);
                    }
                });
    }

    private void renderChannels(String header) {
        showLoading(false);
        hdrChannels.setText(header == null || header.isEmpty()
                ? getString(R.string.col_channels)
                : header.toUpperCase(Locale.US) + "  ·  " + currentItems.size());

        List<RowAdapter.Row> rows = new ArrayList<>();
        for (StreamItem s : currentItems) {
            RowAdapter.Row r = new RowAdapter.Row(s.name, s.subtitle()).tag(s);
            if (s.number > 0) r.badge(String.valueOf(s.number));
            if (prefs.isFavorite(s.favKey())) r.icon("★");
            rows.add(r);
        }
        adChannels.submit(rows);
        if (rows.isEmpty()) {
            showEmpty(getString(R.string.empty));
        } else {
            emptyState.setVisibility(View.GONE);
        }
    }

    private void openEpisodes(final StreamItem series) {
        showLoading(true);
        repo.loadEpisodes(activeServer, series, new ContentRepository.Callback<List<StreamItem>>() {
            @Override
            public void onResult(List<StreamItem> value) {
                currentItems.clear();
                currentItems.addAll(value);
                renderChannels(series.name);
                RowAdapter.focusPosition(rvChannels, 0);
            }

            @Override
            public void onError(String message) {
                showLoading(false);
                toast(message);
            }
        });
    }

    private void showFavorites() {
        List<StreamItem> favs = prefs.getFavoriteItems();
        adCategories.submit(new ArrayList<RowAdapter.Row>());
        hdrCategories.setText("SAVED");
        currentItems.clear();
        currentItems.addAll(favs);
        renderChannels("Favourites");
        if (favs.isEmpty()) {
            showEmpty("No favourites yet.\nLong-press OK on any channel to save it here.");
        }
    }

    // ------------------------------------------------------------------
    // Chrome
    // ------------------------------------------------------------------

    private void updateStatusBar() {
        int tier = monitor.tier();
        String label = NetworkMonitor.tierName(tier) + " · " + monitor.speedLabel()
                + " · " + monitor.connectionLabel();
        netStatus.setText(label);
        int color = tier <= NetworkMonitor.TIER_LOW ? R.color.warn
                : tier == NetworkMonitor.TIER_OFFLINE ? R.color.bad : R.color.good;
        if (tier == NetworkMonitor.TIER_OFFLINE) color = R.color.bad;
        netStatus.setTextColor(getResources().getColor(color));
        clock.setText(new SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())
                .format(new Date()));
    }

    private void fetchNotice() {
        chat.fetchNotice(new ContentRepository.Callback<String>() {
            @Override
            public void onResult(String value) {
                if (value == null || value.trim().isEmpty()) {
                    noticeBar.setVisibility(View.GONE);
                } else {
                    noticeBar.setVisibility(View.VISIBLE);
                    noticeBar.setText("📢  " + value.trim());
                    noticeBar.setSelected(true);
                }
            }

            @Override
            public void onError(String message) {
                noticeBar.setVisibility(View.GONE);
            }
        });
    }

    private void showLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) emptyState.setVisibility(View.GONE);
    }

    private void showEmpty(String message) {
        progress.setVisibility(View.GONE);
        emptyState.setVisibility(View.VISIBLE);
        emptyState.setText(message);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // Remote control
    // ------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case KeyEvent.KEYCODE_SEARCH:
                startActivity(new Intent(this, ServersActivity.class));
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (!currentItems.isEmpty()) {
                    NowPlaying.set(currentItems, Math.max(0, adChannels.activated()),
                            activeCategoryName);
                    startActivity(new Intent(this, PlayerActivity.class));
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        View focused = getCurrentFocus();
        if (focused != null && isDescendant(rvChannels, focused)) {
            RowAdapter.focusPosition(rvCategories, Math.max(0, adCategories.activated()));
            return;
        }
        if (focused != null && isDescendant(rvCategories, focused)) {
            RowAdapter.focusPosition(rvServers, Math.max(0, adServers.activated()));
            return;
        }
        super.onBackPressed();
    }

    private static boolean isDescendant(View parent, View child) {
        View v = child;
        while (v != null) {
            if (v == parent) return true;
            if (!(v.getParent() instanceof View)) return false;
            v = (View) v.getParent();
        }
        return false;
    }
}

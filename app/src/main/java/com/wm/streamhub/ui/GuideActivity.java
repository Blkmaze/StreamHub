package com.wm.streamhub.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;

import com.wm.streamhub.R;
import com.wm.streamhub.data.ContentRepository;
import com.wm.streamhub.data.EpgLoader;
import com.wm.streamhub.data.NowPlaying;
import com.wm.streamhub.data.XtreamClient;
import com.wm.streamhub.model.Category;
import com.wm.streamhub.model.ServerProfile;
import com.wm.streamhub.model.StreamItem;
import com.wm.streamhub.player.PlayerActivity;
import com.wm.streamhub.ui.adapter.RowAdapter;
import com.wm.streamhub.util.Prefs;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Categories -> Channels -> (this screen's own program strip) -> Player, all on one
 * screen, reached from the Home rail. Real channels come from whichever server is
 * active on Home; the "now playing" strip is honest about not having real schedule
 * data until a server has an XMLTV/EPG URL configured (see ServerProfile.epgUrl).
 *
 * Selecting a channel is a two-step gesture, same as Apple TV / Google TV:
 *   1st OK  -> preview panel (channel info, no playback yet)
 *   2nd OK (or the Watch button) -> PlayerActivity, full screen
 *   BACK while previewing -> dismiss the preview and stay on the guide
 *
 * Two things used to be permanent stand-ins here no matter what: the preview
 * box always showed a static ▶ glyph (never real video), and every channel's
 * subtitle always said "no guide data yet" even when the server had a real
 * XMLTV/EPG URL configured (that field existed on ServerProfile but nothing
 * ever read it). Both are now live: a muted mini player fills the preview box
 * (after a short dwell so fast browsing doesn't open a stream per channel),
 * and EpgLoader parses the configured XMLTV feed for real "on now"/"up next".
 */
@UnstableApi
public class GuideActivity extends AppCompatActivity {

    private Prefs prefs;
    private ContentRepository repo;

    private TextView guideServerLabel, hdrGuideChannels, guideEmptyState;
    private LinearLayout chipRow, previewPanel;
    private ProgressBar guideProgress;
    private RecyclerView rvChannels;
    private RowAdapter adChannels;

    private TextView pvLogo, pvChan, pvTitle, pvSub, pvWatch;
    private PlayerView pvPlayer;
    private ExoPlayer previewPlayer;
    private final Handler previewHandler = new Handler(Looper.getMainLooper());

    private ServerProfile activeServer;
    private EpgLoader epg;
    private final List<Category> categories = new ArrayList<>();
    private final List<StreamItem> currentItems = new ArrayList<>();
    private String activeCategoryId = "";
    private String activeCategoryName = "";
    private int activeChipIndex = 0;

    /** Position in currentItems currently sitting in the preview panel, or -1. */
    private int previewPosition = -1;
    private StreamItem previewItem;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);

        prefs = new Prefs(this);
        repo = ContentRepository.get(this);

        guideServerLabel = findViewById(R.id.guideServerLabel);
        hdrGuideChannels = findViewById(R.id.hdrGuideChannels);
        guideEmptyState = findViewById(R.id.guideEmptyState);
        guideProgress = findViewById(R.id.guideProgress);
        chipRow = findViewById(R.id.chipRow);
        previewPanel = findViewById(R.id.previewPanel);
        rvChannels = findViewById(R.id.listGuideChannels);

        pvLogo = findViewById(R.id.pvLogo);
        pvPlayer = findViewById(R.id.pvPlayer);
        pvChan = findViewById(R.id.pvChan);
        pvTitle = findViewById(R.id.pvTitle);
        pvSub = findViewById(R.id.pvSub);
        pvWatch = findViewById(R.id.pvWatch);
        pvWatch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commit();
            }
        });

        adChannels = RowAdapter.attach(rvChannels);
        adChannels.setOnClick(new RowAdapter.OnClick() {
            @Override
            public void onClick(int position, RowAdapter.Row row) {
                if (!(row.tag instanceof StreamItem)) return;
                if (position == previewPosition) {
                    commit();
                    return;
                }
                previewPosition = position;
                previewItem = (StreamItem) row.tag;
                adChannels.setActivated(position);
                showPreview(previewItem);
            }
        });

        loadActiveServer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The active server (or its channel list) may have changed on Home since we
        // were last here.
        loadActiveServer();
        if (previewItem != null) startPreviewPlayback(previewItem);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Don't leave a muted stream open against the panel while this screen
        // isn't visible -- costs the customer a connection slot for nothing.
        stopPreviewPlayback();
        previewHandler.removeCallbacksAndMessages(null);
    }

    private void loadActiveServer() {
        List<ServerProfile> servers = prefs.getServers();
        ServerProfile chosen = null;
        String activeId = prefs.getActiveServerId();
        for (ServerProfile s : servers) {
            if (s.id.equals(activeId)) chosen = s;
        }
        if (chosen == null && !servers.isEmpty()) chosen = servers.get(0);

        if (chosen == null) {
            activeServer = null;
            epg = null;
            guideServerLabel.setText("");
            chipRow.removeAllViews();
            adChannels.submit(new ArrayList<RowAdapter.Row>());
            showEmpty(getString(R.string.guide_no_server));
            return;
        }

        if (chosen == activeServer || chosen.id.equals(activeServer == null ? "" : activeServer.id)) {
            return; // already showing this server, don't reset the user's chip/scroll position
        }
        activeServer = chosen;
        epg = null;
        guideServerLabel.setText(activeServer.label());
        loadEpgIfConfigured();
        loadCategories();
    }

    /** Fetches the server's XMLTV feed if one is configured (Servers -> edit -> EPG URL).
     *  Silently leaves epg null on failure -- channel rows already fall back to the
     *  honest "no guide data yet" placeholder in that case. */
    private void loadEpgIfConfigured() {
        final ServerProfile forServer = activeServer;
        if (forServer == null || forServer.epgUrl == null || forServer.epgUrl.trim().isEmpty()) return;
        repo.loadEpg(forServer, new ContentRepository.Callback<EpgLoader>() {
            @Override
            public void onResult(EpgLoader value) {
                if (activeServer != forServer) return; // user switched servers while this was loading
                epg = value;
                renderChannels();
                if (previewItem != null) showPreview(previewItem);
            }

            @Override
            public void onError(String message) {
                // No EPG URL set, or the feed couldn't be read -- stay on the
                // sample-listing placeholder rather than showing an error toast
                // for what is often just "reseller hasn't set one up yet."
            }
        });
    }

    private void loadCategories() {
        showLoading(true);
        repo.loadCategories(activeServer, StreamItem.KIND_LIVE,
                new ContentRepository.Callback<List<Category>>() {
                    @Override
                    public void onResult(List<Category> value) {
                        categories.clear();
                        categories.addAll(value);
                        buildChips();
                        activeChipIndex = 0;
                        activeCategoryId = "";
                        activeCategoryName = getString(R.string.guide_chip_all);
                        loadChannels();
                    }

                    @Override
                    public void onError(String message) {
                        showLoading(false);
                        chipRow.removeAllViews();
                        adChannels.submit(new ArrayList<RowAdapter.Row>());
                        showEmpty(message);
                    }
                });
    }

    private void loadChannels() {
        showLoading(true);
        hidePreview();
        repo.loadStreams(activeServer, StreamItem.KIND_LIVE, activeCategoryId,
                new ContentRepository.Callback<List<StreamItem>>() {
                    @Override
                    public void onResult(List<StreamItem> value) {
                        currentItems.clear();
                        currentItems.addAll(value);
                        renderChannels();
                    }

                    @Override
                    public void onError(String message) {
                        showLoading(false);
                        adChannels.submit(new ArrayList<RowAdapter.Row>());
                        showEmpty(message);
                    }
                });
    }

    private void renderChannels() {
        showLoading(false);
        hdrGuideChannels.setText(currentItems.isEmpty()
                ? getString(R.string.col_channels)
                : activeCategoryName.toUpperCase(Locale.US) + "  ·  " + currentItems.size());

        List<RowAdapter.Row> rows = new ArrayList<>();
        for (StreamItem s : currentItems) {
            RowAdapter.Row r = new RowAdapter.Row(s.name, channelSubtitle(s)).tag(s);
            if (s.number > 0) r.badge(String.valueOf(s.number));
            rows.add(r);
        }
        adChannels.submit(rows);
        if (rows.isEmpty()) {
            showEmpty(getString(R.string.guide_no_channels));
        } else {
            guideEmptyState.setVisibility(View.GONE);
            RowAdapter.focusPosition(rvChannels, 0);
        }
    }

    // ------------------------------------------------------------------
    // Category chips
    // ------------------------------------------------------------------

    private void buildChips() {
        chipRow.removeAllViews();
        addChip(getString(R.string.guide_chip_all), null, 0);
        for (int i = 0; i < categories.size(); i++) {
            addChip(categories.get(i).name, categories.get(i), i + 1);
        }
    }

    private void addChip(String label, final Category category, final int chipIndex) {
        TextView chip = new TextView(this);
        int padH = dp(16), padV = dp(8);
        chip.setPadding(padH, padV, padH, padV);
        chip.setText(label);
        chip.setTextSize(13);
        chip.setTextColor(getResources().getColor(R.color.text_primary));
        chip.setBackgroundResource(R.drawable.bg_chip);
        chip.setFocusable(true);
        chip.setActivated(chipIndex == activeChipIndex);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (chipIndex == activeChipIndex) return;
                activeChipIndex = chipIndex;
                for (int i = 0; i < chipRow.getChildCount(); i++) {
                    chipRow.getChildAt(i).setActivated(i == chipIndex);
                }
                activeCategoryId = category == null ? "" : category.id;
                activeCategoryName = category == null ? getString(R.string.guide_chip_all) : category.name;
                loadChannels();
            }
        });
        chipRow.addView(chip);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // Preview / commit (the two-step select)
    // ------------------------------------------------------------------

    private void showPreview(StreamItem item) {
        previewPanel.setVisibility(View.VISIBLE);
        pvChan.setText(item.name.toUpperCase(Locale.US));

        EpgLoader.Programme now = epg != null ? epg.now(item.epgChannelId) : null;
        EpgLoader.Programme next = epg != null ? epg.next(item.epgChannelId) : null;
        String sub = item.subtitle();
        if (now != null) {
            pvTitle.setText(now.title.isEmpty() ? item.name : now.title);
            StringBuilder body = new StringBuilder(fmtRange(now.startMs, now.stopMs));
            if (next != null && !next.title.isEmpty()) {
                body.append("\n\nUp next: ").append(next.title)
                        .append("  ·  ").append(fmtRange(next.startMs, next.stopMs));
            }
            pvSub.setText((sub.isEmpty() ? "" : sub + "\n\n") + body);
        } else {
            pvTitle.setText(getString(R.string.guide_no_data_title));
            pvSub.setText((sub.isEmpty() ? "" : sub + "\n\n") + getString(R.string.guide_no_data_body));
        }

        startPreviewPlayback(item);
    }

    private void hidePreview() {
        previewPanel.setVisibility(View.GONE);
        previewPosition = -1;
        previewItem = null;
        adChannels.setActivated(-1);
        stopPreviewPlayback();
        previewHandler.removeCallbacksAndMessages(null);
    }

    private void commit() {
        if (previewItem == null || previewPosition < 0) return;
        // Release the mini preview's connection before PlayerActivity opens its
        // own -- most Xtream panels only allow 1-2 concurrent streams per
        // account, so holding both at once could cost the customer the very
        // channel they're trying to watch.
        stopPreviewPlayback();
        previewHandler.removeCallbacksAndMessages(null);
        NowPlaying.set(currentItems, previewPosition, activeCategoryName);
        startActivity(new Intent(GuideActivity.this, PlayerActivity.class));
    }

    // ------------------------------------------------------------------
    // EPG-aware subtitle + the muted mini preview player
    // ------------------------------------------------------------------

    private String channelSubtitle(StreamItem s) {
        if (epg != null && s.epgChannelId != null && !s.epgChannelId.isEmpty()) {
            EpgLoader.Programme now = epg.now(s.epgChannelId);
            if (now != null && !now.title.isEmpty()) return "Now: " + now.title;
            if (epg.hasChannel(s.epgChannelId)) return "Live now  ·  schedule available";
        }
        return "Live now  ·  no guide data yet";
    }

    private String fmtRange(long startMs, long stopMs) {
        SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.US);
        return f.format(new Date(startMs)) + " – " + f.format(new Date(stopMs));
    }

    /** Waits for a short dwell before actually opening the stream, so arrowing
     *  quickly through the channel list doesn't open (and immediately abandon)
     *  a connection per channel -- that would only add to the buffering the
     *  customer is already dealing with, not fix it. */
    private void startPreviewPlayback(final StreamItem item) {
        previewHandler.removeCallbacksAndMessages(null);
        stopPreviewPlayback();
        pvLogo.setVisibility(View.VISIBLE);
        previewHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (item != previewItem) return; // user has since moved to another channel
                playPreview(item);
            }
        }, 450);
    }

    private void playPreview(StreamItem item) {
        try {
            String url = resolvePreviewUrl(item);
            if (url == null || url.isEmpty()) return;
            previewPlayer = new ExoPlayer.Builder(this).build();
            pvPlayer.setPlayer(previewPlayer);
            previewPlayer.setVolume(0f);
            MediaItem.Builder mb = new MediaItem.Builder().setUri(url);
            if (url.contains(".m3u8")) mb.setMimeType(MimeTypes.APPLICATION_M3U8);
            previewPlayer.setMediaItem(mb.build());
            previewPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) pvLogo.setVisibility(View.GONE);
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    pvLogo.setVisibility(View.VISIBLE);
                }
            });
            previewPlayer.prepare();
            previewPlayer.setPlayWhenReady(true);
        } catch (Exception ignored) {
            pvLogo.setVisibility(View.VISIBLE);
        }
    }

    private String resolvePreviewUrl(StreamItem item) {
        ServerProfile server = prefs.getServer(item.serverId);
        if (server == null || !server.isXtream()) return item.directUrl;
        return new XtreamClient(server).streamUrl(item, true);
    }

    private void stopPreviewPlayback() {
        if (pvPlayer != null) pvPlayer.setPlayer(null);
        if (previewPlayer != null) {
            try {
                previewPlayer.release();
            } catch (Exception ignored) {
            }
            previewPlayer = null;
        }
    }

    // ------------------------------------------------------------------

    private void showLoading(boolean loading) {
        guideProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) guideEmptyState.setVisibility(View.GONE);
    }

    private void showEmpty(String message) {
        guideProgress.setVisibility(View.GONE);
        guideEmptyState.setVisibility(View.VISIBLE);
        guideEmptyState.setText(message);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && previewPosition >= 0) {
            hidePreview();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (previewPosition >= 0) {
            hidePreview();
            return;
        }
        super.onBackPressed();
    }
}

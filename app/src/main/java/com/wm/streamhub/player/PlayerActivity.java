package com.wm.streamhub.player;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;

import com.wm.streamhub.R;
import com.wm.streamhub.data.ContentRepository;
import com.wm.streamhub.data.NowPlaying;
import com.wm.streamhub.data.XtreamClient;
import com.wm.streamhub.model.ServerProfile;
import com.wm.streamhub.model.StreamItem;
import com.wm.streamhub.net.AdaptiveEngine;
import com.wm.streamhub.net.NetworkMonitor;
import com.wm.streamhub.ui.adapter.RowAdapter;
import com.wm.streamhub.util.Prefs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Playback surface plus the runtime half of the adaptive engine.
 *
 * The player is rebuilt (not just reconfigured) when the buffer profile has to
 * change, because LoadControl is fixed for the life of an ExoPlayer instance.
 * Bitrate ceilings, by contrast, are applied live via TrackSelectionParameters.
 */
@UnstableApi
public class PlayerActivity extends AppCompatActivity implements Player.Listener {

    private Prefs prefs;
    private AdaptiveEngine engine;
    private NetworkMonitor monitor;
    private ContentRepository repo;

    private PlayerView playerView;
    private ExoPlayer player;

    private LinearLayout bufferBox, errorBox, quickList, qualityMenu, infoBar;
    private TextView bufferText, bufferSub, statsBox, infoNumber, infoTitle,
            infoSubtitle, infoQuality, errorMsg, errorAction, quickHeader;
    private RecyclerView quickRecycler, qualityRecycler;
    private RowAdapter quickAdapter, qualityAdapter;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private StreamItem currentItem;
    private boolean usingHls = true;
    private int retryCount = 0;
    private boolean everReady = false;
    private long lastReadyAt = 0;
    private int lastProfileUsed = -1;
    private boolean failoverTried = false;

    /**
     * A stall this short is expected to be absorbed by the live cushion (see
     * AdaptiveEngine#liveTargetOffsetMs) — nothing is shown on screen for it. Only a
     * stall that outlasts this grace window is a real, user-visible problem.
     *
     * Widened from 1.5s: these run on Fire TV Stick hardware (weak CPUs, not the
     * top-tier Fire TV Cube), which sees more brief decode hiccups than a phone or
     * a fast set-top box. A longer grace window means those short hiccups get
     * absorbed silently instead of flashing a notice for something that resolves
     * on its own within a couple seconds.
     */
    private static final long STALL_GRACE_MS = 3000L;
    private Runnable pendingBufferOverlay;

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            refreshStats();
            maybeRecoverQuality();
            ui.postDelayed(this, 1000);
        }
    };

    private final Runnable hideInfo = new Runnable() {
        @Override
        public void run() {
            infoBar.setVisibility(View.GONE);
        }
    };

    // ------------------------------------------------------------------

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        prefs = new Prefs(this);
        engine = new AdaptiveEngine(this);
        monitor = NetworkMonitor.get(this);
        repo = ContentRepository.get(this);

        playerView = findViewById(R.id.playerView);
        bufferBox = findViewById(R.id.bufferBox);
        bufferText = findViewById(R.id.bufferText);
        bufferSub = findViewById(R.id.bufferSub);
        statsBox = findViewById(R.id.statsBox);
        infoBar = findViewById(R.id.infoBar);
        infoNumber = findViewById(R.id.infoNumber);
        infoTitle = findViewById(R.id.infoTitle);
        infoSubtitle = findViewById(R.id.infoSubtitle);
        infoQuality = findViewById(R.id.infoQuality);
        errorBox = findViewById(R.id.errorBox);
        errorMsg = findViewById(R.id.errorMsg);
        errorAction = findViewById(R.id.errorAction);
        quickList = findViewById(R.id.quickList);
        quickHeader = findViewById(R.id.quickHeader);
        quickRecycler = findViewById(R.id.quickRecycler);
        qualityMenu = findViewById(R.id.qualityMenu);
        qualityRecycler = findViewById(R.id.qualityRecycler);

        quickAdapter = RowAdapter.attach(quickRecycler);
        qualityAdapter = RowAdapter.attach(qualityRecycler);
        buildQuickList();
        wireMenus();

        currentItem = NowPlaying.current();
        if (currentItem == null) {
            Toast.makeText(this, "Nothing to play", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        statsBox.setVisibility(prefs.isShowStats() ? View.VISIBLE : View.GONE);
        openStream(currentItem, true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        monitor.persist();
    }

    // ------------------------------------------------------------------
    // Player lifecycle
    // ------------------------------------------------------------------

    private void buildPlayer() {
        releasePlayer();

        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)   // Fire TV sticks fall back to SW decode
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);

        ServerProfile server = prefs.getServer(currentItem.serverId);
        String ua = server == null ? "" : server.userAgent;

        DefaultMediaSourceFactory sources =
                new DefaultMediaSourceFactory(engine.buildDataSourceFactory(ua));

        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(sources)
                .setLoadControl(engine.buildLoadControl())
                .setTrackSelector(engine.buildTrackSelector())
                .setBandwidthMeter(monitor.meter())
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(30_000)
                .build();

        player.setWakeMode(C.WAKE_MODE_NETWORK);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(this);
        playerView.setPlayer(player);
        playerView.setKeepContentOnPlayerReset(true);
        lastProfileUsed = engine.effectiveProfile();
    }

    private void releasePlayer() {
        cancelPendingBufferOverlay();
        if (player != null) {
            try {
                player.removeListener(this);
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    private void openStream(StreamItem item, boolean resetAdaptive) {
        currentItem = item;
        if (resetAdaptive) {
            engine.resetForNewStream();
            retryCount = 0;
            failoverTried = false;
            everReady = false;
        }

        ServerProfile server = prefs.getServer(item.serverId);
        usingHls = server != null && engine.preferHls(server.preferHls);

        buildPlayer();
        String url = resolveUrl(item, usingHls);
        if (url == null || url.isEmpty()) {
            showError("This entry has no playable address.", "Pick another channel");
            return;
        }

        MediaItem.Builder mb = new MediaItem.Builder().setUri(url);
        if (url.contains(".m3u8")) {
            mb.setMimeType(MimeTypes.APPLICATION_M3U8);
            if (item.kind == StreamItem.KIND_LIVE) {
                // Deliberately trail the true live edge by a cushion sized to the
                // buffer profile, so a throughput dip drains the cushion instead of
                // ever reaching the screen. ExoPlayer nudges playback speed to stay
                // inside [min,max] rather than pausing outright.
                mb.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(engine.liveTargetOffsetMs())
                        .setMinOffsetMs(engine.liveMinOffsetMs())
                        .setMaxOffsetMs(engine.liveMaxOffsetMs())
                        .setMinPlaybackSpeed(0.97f)
                        .setMaxPlaybackSpeed(1.03f)
                        .build());
            }
        } else if (url.endsWith(".ts")) {
            mb.setMimeType(MimeTypes.VIDEO_MP2T);
        } else if (url.contains(".mpd")) {
            mb.setMimeType(MimeTypes.APPLICATION_MPD);
        }

        hideError();
        showBuffering(true, "Connecting…", engine.statusLine());

        player.setMediaItem(mb.build());
        player.prepare();
        player.setPlayWhenReady(true);

        updateInfoBar(true);
        NowPlaying.setIndex(indexOf(item));
        quickAdapter.setActivated(NowPlaying.index());

        ui.removeCallbacks(statsTick);
        ui.post(statsTick);
    }

    private String resolveUrl(StreamItem item, boolean hls) {
        ServerProfile server = prefs.getServer(item.serverId);
        if (server == null || !server.isXtream()) {
            return item.directUrl;
        }
        return new XtreamClient(server).streamUrl(item, hls);
    }

    private int indexOf(StreamItem item) {
        List<StreamItem> q = NowPlaying.queue();
        for (int i = 0; i < q.size(); i++) {
            if (q.get(i) == item) return i;
            if (q.get(i).id.equals(item.id) && q.get(i).serverId.equals(item.serverId)) return i;
        }
        return NowPlaying.index();
    }

    // ------------------------------------------------------------------
    // Player callbacks
    // ------------------------------------------------------------------

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_BUFFERING) {
            if (everReady) {
                // A stall during playback: the line just got worse than the stream.
                // The fixes (lower ceiling, deeper buffer) apply immediately either
                // way; only the on-screen spinner waits out the grace window, so a
                // stall the live cushion swallows on its own never reaches the screen.
                engine.onRebuffer();
                engine.retune(player);
                scheduleBufferOverlay("Adjusting for your connection…",
                        "Lowering quality · " + engine.statusLine());
                maybeDeepenBuffer();
            } else {
                showBuffering(true, "Connecting…", engine.statusLine());
            }
        } else if (state == Player.STATE_READY) {
            everReady = true;
            lastReadyAt = System.currentTimeMillis();
            retryCount = 0;
            cancelPendingBufferOverlay();
            showBuffering(false, null, null);
            hideError();
            updateInfoBar(false);
        } else if (state == Player.STATE_ENDED) {
            if (currentItem != null && currentItem.kind == StreamItem.KIND_LIVE) {
                // Live streams should not end - treat it as a dropped connection.
                scheduleRetry("The stream ended unexpectedly.");
            } else {
                finish();
            }
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        String reason = error.getErrorCodeName();
        boolean decoderProblem = error.errorCode >= PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                && error.errorCode <= PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;

        if (decoderProblem) {
            // Ask for a smaller stream: many sticks choke on 4K/HEVC but play 1080p fine.
            engine.onRebuffer();
            engine.onRebuffer();
            engine.retune(player);
        }
        scheduleRetry(humanError(error) + "  (" + reason + ")");
    }

    @Override
    public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
        updateInfoBar(false);
    }

    // ------------------------------------------------------------------
    // Recovery
    // ------------------------------------------------------------------

    private void scheduleRetry(final String message) {
        retryCount++;
        if (retryCount <= 2) {
            // First recoveries: flip the transport. HLS and TS fail in different ways.
            usingHls = !usingHls;
            showError(message, "Reconnecting on " + (usingHls ? "HLS" : "direct TS") + "…");
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isFinishing() || currentItem == null) return;
                    reopenSameItem();
                }
            }, 1500L * retryCount);
            return;
        }

        if (prefs.isAutoFailover() && !failoverTried) {
            failoverTried = true;
            showError(message, "Looking for this channel on your other servers…");
            repo.findAlternatives(prefs.getServers(), currentItem,
                    new ContentRepository.Callback<List<StreamItem>>() {
                        @Override
                        public void onResult(List<StreamItem> value) {
                            if (isFinishing()) return;
                            if (value.isEmpty()) {
                                showError(message, "No backup server has this channel. "
                                        + "Press BACK to choose another.");
                                return;
                            }
                            StreamItem alt = value.get(0);
                            Toast.makeText(PlayerActivity.this,
                                    "Switched to " + alt.serverName, Toast.LENGTH_SHORT).show();
                            retryCount = 0;
                            openStream(alt, false);
                        }

                        @Override
                        public void onError(String m) {
                            showError(message, "Press BACK to choose another channel.");
                        }
                    });
            return;
        }

        if (retryCount <= 6) {
            showError(message, "Retrying in " + (retryCount * 3) + "s…");
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isFinishing() || currentItem == null) return;
                    reopenSameItem();
                }
            }, retryCount * 3000L);
        } else {
            showError(message, "Giving up. Press BACK to choose another channel.");
        }
    }

    private void reopenSameItem() {
        StreamItem item = currentItem;
        buildPlayer();
        String url = resolveUrl(item, usingHls);
        MediaItem.Builder mb = new MediaItem.Builder().setUri(url);
        if (url.contains(".m3u8")) mb.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (url.endsWith(".ts")) mb.setMimeType(MimeTypes.VIDEO_MP2T);
        player.setMediaItem(mb.build());
        player.prepare();
        player.setPlayWhenReady(true);
        showBuffering(true, "Reconnecting…", engine.statusLine());
    }

    /** If the buffer profile the engine now wants differs from the live one, rebuild. */
    private void maybeDeepenBuffer() {
        int wanted = engine.effectiveProfile();
        if (wanted == lastProfileUsed) return;
        long position = player == null ? 0 : player.getCurrentPosition();
        boolean live = currentItem != null && currentItem.kind == StreamItem.KIND_LIVE;
        reopenSameItem();
        if (!live && player != null && position > 0) player.seekTo(position);
    }

    private void maybeRecoverQuality() {
        if (player == null || !everReady) return;
        if (player.getPlaybackState() != Player.STATE_READY) return;
        if (System.currentTimeMillis() - lastReadyAt < 20_000L) return;
        if (engine.onHealthyTick()) {
            engine.retune(player);
        }
    }

    // ------------------------------------------------------------------
    // Overlays
    // ------------------------------------------------------------------

    private void showBuffering(boolean show, String title, String sub) {
        bufferBox.setVisibility(show ? View.VISIBLE : View.GONE);
        if (title != null) bufferText.setText(title);
        if (sub != null) bufferSub.setText(sub);
    }

    /** Only puts the spinner on screen if the stall outlasts STALL_GRACE_MS. */
    private void scheduleBufferOverlay(final String title, final String sub) {
        if (pendingBufferOverlay != null) return; // already counting down for this stall
        pendingBufferOverlay = new Runnable() {
            @Override
            public void run() {
                pendingBufferOverlay = null;
                showBuffering(true, title, sub);
            }
        };
        ui.postDelayed(pendingBufferOverlay, STALL_GRACE_MS);
    }

    private void cancelPendingBufferOverlay() {
        if (pendingBufferOverlay != null) {
            ui.removeCallbacks(pendingBufferOverlay);
            pendingBufferOverlay = null;
        }
    }

    private void showError(String message, String action) {
        showBuffering(false, null, null);
        errorBox.setVisibility(View.VISIBLE);
        errorMsg.setText(message);
        errorAction.setText(action);
    }

    private void hideError() {
        errorBox.setVisibility(View.GONE);
    }

    private void updateInfoBar(boolean show) {
        if (currentItem == null) return;
        infoNumber.setText(currentItem.number > 0
                ? String.valueOf(currentItem.number) : "•");
        infoTitle.setText(currentItem.name);
        infoSubtitle.setText(currentItem.subtitle()
                + (NowPlaying.title().isEmpty() ? "" : "   ·   " + NowPlaying.title()));
        infoQuality.setText(qualityLabel());
        if (show) {
            infoBar.setVisibility(View.VISIBLE);
            ui.removeCallbacks(hideInfo);
            ui.postDelayed(hideInfo, 6000);
        }
    }

    private String qualityLabel() {
        if (player == null) return engine.profileName();
        Format f = player.getVideoFormat();
        if (f == null) return monitor.speedLabel();
        String res = f.height > 0 ? f.height + "p" : "";
        String rate = f.bitrate > 0
                ? " · " + (f.bitrate / 1000) + "k" : "";
        return (res + rate).trim();
    }

    private void refreshStats() {
        if (statsBox.getVisibility() != View.VISIBLE || player == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("NET   ").append(monitor.connectionLabel())
                .append("  ").append(monitor.speedLabel())
                .append("  (").append(NetworkMonitor.tierName(monitor.tier())).append(")\n");
        sb.append("MODE  ").append(engine.profileName())
                .append("  cap ").append(engine.maxVideoBitrate() / 1000).append("k")
                .append("  x").append(String.format(Locale.US, "%.2f", engine.penalty()))
                .append("\n");
        Format v = player.getVideoFormat();
        if (v != null) {
            sb.append("VIDEO ").append(v.width).append("x").append(v.height);
            if (v.bitrate > 0) sb.append("  ").append(v.bitrate / 1000).append("k");
            if (v.frameRate > 0) sb.append("  ").append(Math.round(v.frameRate)).append("fps");
            if (v.sampleMimeType != null) sb.append("  ").append(v.sampleMimeType.replace("video/", ""));
            sb.append("\n");
        }
        Format a = player.getAudioFormat();
        if (a != null) {
            sb.append("AUDIO ").append(a.sampleMimeType == null ? "?"
                    : a.sampleMimeType.replace("audio/", ""));
            if (a.channelCount > 0) sb.append("  ").append(a.channelCount).append("ch");
            sb.append("\n");
        }
        sb.append("BUF   ").append(player.getTotalBufferedDuration() / 1000).append("s")
                .append("   stalls ").append(engine.rebufferCount());
        sb.append("\nLINK  ").append(usingHls ? "HLS adaptive" : "direct TS");
        statsBox.setText(sb.toString());
    }

    // ------------------------------------------------------------------
    // Quick list + playback menu
    // ------------------------------------------------------------------

    private void buildQuickList() {
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (StreamItem s : NowPlaying.queue()) {
            RowAdapter.Row r = new RowAdapter.Row(s.name, s.subtitle()).tag(s);
            if (s.number > 0) r.badge(String.valueOf(s.number));
            rows.add(r);
        }
        quickAdapter.submit(rows);
        quickAdapter.setActivated(NowPlaying.index());
        quickHeader.setText(NowPlaying.title().isEmpty()
                ? getString(R.string.col_channels) : NowPlaying.title());
        quickAdapter.setOnClick(new RowAdapter.OnClick() {
            @Override
            public void onClick(int position, RowAdapter.Row row) {
                if (!(row.tag instanceof StreamItem)) return;
                NowPlaying.setIndex(position);
                quickAdapter.setActivated(position);
                toggleQuickList(false);
                openStream((StreamItem) row.tag, true);
            }
        });
    }

    private void wireMenus() {
        qualityAdapter.setOnClick(new RowAdapter.OnClick() {
            @Override
            public void onClick(int position, RowAdapter.Row row) {
                String action = String.valueOf(row.tag);
                if ("adaptive".equals(action)) {
                    prefs.setAdaptiveEnabled(!prefs.isAdaptiveEnabled());
                } else if ("profile".equals(action)) {
                    int p = (prefs.getBufferProfile() + 1) % 4;
                    prefs.setBufferProfile(p);
                } else if ("cap".equals(action)) {
                    int[] caps = {0, 8000, 4500, 2500, 1200};
                    int cur = prefs.getMaxBitrateKbps();
                    int next = caps[0];
                    for (int i = 0; i < caps.length; i++) {
                        if (caps[i] == cur) {
                            next = caps[(i + 1) % caps.length];
                            break;
                        }
                    }
                    prefs.setMaxBitrateKbps(next);
                } else if ("stats".equals(action)) {
                    prefs.setShowStats(!prefs.isShowStats());
                    statsBox.setVisibility(prefs.isShowStats() ? View.VISIBLE : View.GONE);
                } else if ("transport".equals(action)) {
                    usingHls = !usingHls;
                    reopenSameItem();
                } else if ("reload".equals(action)) {
                    openStream(currentItem, true);
                }
                buildQualityMenu();
                if (player != null) engine.retune(player);
                maybeDeepenBuffer();
            }
        });
    }

    private void buildQualityMenu() {
        List<RowAdapter.Row> rows = new ArrayList<>();
        rows.add(new RowAdapter.Row("Adapt to my internet speed",
                prefs.isAdaptiveEnabled() ? "On — quality follows your line"
                        : "Off — always request the highest quality")
                .badge(prefs.isAdaptiveEnabled() ? "ON" : "OFF").tag("adaptive"));

        String profile;
        switch (prefs.getBufferProfile()) {
            case Prefs.PROFILE_ANTI_BUFFER: profile = "Anti-buffer (deep buffer)"; break;
            case Prefs.PROFILE_BALANCED: profile = "Balanced"; break;
            case Prefs.PROFILE_LOW_LATENCY: profile = "Low latency"; break;
            default: profile = "Auto — now: " + engine.profileName(); break;
        }
        rows.add(new RowAdapter.Row("Buffering mode", profile).tag("profile"));

        int cap = prefs.getMaxBitrateKbps();
        rows.add(new RowAdapter.Row("Quality ceiling",
                cap == 0 ? "Automatic" : cap + " kbps max").tag("cap"));

        rows.add(new RowAdapter.Row("Stream link",
                usingHls ? "HLS (adaptive)" : "Direct TS (lower latency)").tag("transport"));

        rows.add(new RowAdapter.Row("On-screen diagnostics",
                prefs.isShowStats() ? "Visible" : "Hidden")
                .badge(prefs.isShowStats() ? "ON" : "OFF").tag("stats"));

        rows.add(new RowAdapter.Row("Reload this channel", "Fresh connection").tag("reload"));
        qualityAdapter.submit(rows);
    }

    private void toggleQuickList(boolean show) {
        quickList.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            qualityMenu.setVisibility(View.GONE);
            RowAdapter.focusPosition(quickRecycler, NowPlaying.index());
        } else {
            playerView.requestFocus();
        }
    }

    private void toggleQualityMenu(boolean show) {
        if (show) buildQualityMenu();
        qualityMenu.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            quickList.setVisibility(View.GONE);
            RowAdapter.focusPosition(qualityRecycler, 0);
        } else {
            playerView.requestFocus();
        }
    }

    // ------------------------------------------------------------------
    // Remote control
    // ------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean listOpen = quickList.getVisibility() == View.VISIBLE;
        boolean menuOpen = qualityMenu.getVisibility() == View.VISIBLE;

        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
                toggleQualityMenu(!menuOpen);
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (listOpen) {
                    toggleQuickList(false);
                    return true;
                }
                if (menuOpen) {
                    toggleQualityMenu(false);
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (!listOpen && !menuOpen) {
                    zap(-1);
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (!listOpen && !menuOpen) {
                    zap(1);
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!listOpen && !menuOpen) {
                    toggleQuickList(true);
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_INFO:
                if (!listOpen && !menuOpen) {
                    updateInfoBar(true);
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!listOpen && !menuOpen) {
                    prefs.setShowStats(!prefs.isShowStats());
                    statsBox.setVisibility(prefs.isShowStats() ? View.VISIBLE : View.GONE);
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (player != null && currentItem != null
                        && currentItem.kind != StreamItem.KIND_LIVE) {
                    player.setPlayWhenReady(!player.getPlayWhenReady());
                    updateInfoBar(true);
                    return true;
                }
                break;

            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void zap(int delta) {
        StreamItem next = NowPlaying.step(delta);
        if (next == null) return;
        quickAdapter.setActivated(NowPlaying.index());
        openStream(next, true);
    }

    private static String humanError(PlaybackException e) {
        int code = e.errorCode;
        if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return "Lost connection to the server.";
        }
        if (code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            return "The server refused the stream. Your line may be at its connection limit.";
        }
        if (code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            return "That channel is not available on this server right now.";
        }
        if (code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                || code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
            return "This device cannot decode that stream format.";
        }
        if (code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) {
            return "The stream data was corrupt.";
        }
        return "Playback problem.";
    }
}

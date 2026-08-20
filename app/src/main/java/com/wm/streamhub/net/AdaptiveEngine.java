package com.wm.streamhub.net;

import android.content.Context;

import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.wm.streamhub.util.Prefs;

/**
 * Turns "how fast is this person's internet" into concrete player settings.
 *
 * Two loops run here:
 *
 *  - Coarse loop (per stream): pick a buffer profile and a starting bitrate ceiling
 *    from the measured tier. A slow line gets a much deeper buffer so a dip in
 *    throughput is absorbed instead of turning into a spinner.
 *
 *  - Fine loop (during playback): every rebuffer event drops the ceiling by 30% and
 *    deepens the buffer; every 45 s of clean playback lifts it back up one step.
 *    That is what stops a stream from oscillating between 1080p and a stall.
 */
@UnstableApi
public class AdaptiveEngine {

    private final Context ctx;
    private final Prefs prefs;
    private final NetworkMonitor monitor;

    /** Multiplier applied to the tier ceiling, tightened on every rebuffer. */
    private float penalty = 1.0f;
    private int rebufferCount = 0;
    private long lastRebufferAt = 0;
    private long lastRecoveryAt = 0;

    public AdaptiveEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = new Prefs(this.ctx);
        this.monitor = NetworkMonitor.get(this.ctx);
    }

    // ------------------------------------------------------------------
    // Buffer sizing
    // ------------------------------------------------------------------

    /** Resolves PROFILE_AUTO into a concrete profile using the measured tier. */
    public int effectiveProfile() {
        int p = prefs.getBufferProfile();
        if (p != Prefs.PROFILE_AUTO) return p;
        if (!prefs.isAdaptiveEnabled()) return Prefs.PROFILE_BALANCED;
        switch (monitor.tier()) {
            case NetworkMonitor.TIER_OFFLINE:
            case NetworkMonitor.TIER_VERY_LOW:
            case NetworkMonitor.TIER_LOW:
                return Prefs.PROFILE_ANTI_BUFFER;
            case NetworkMonitor.TIER_VERY_HIGH:
                return Prefs.PROFILE_LOW_LATENCY;
            default:
                return Prefs.PROFILE_BALANCED;
        }
    }

    public String profileName() {
        switch (effectiveProfile()) {
            case Prefs.PROFILE_ANTI_BUFFER: return "Anti-buffer";
            case Prefs.PROFILE_LOW_LATENCY: return "Low latency";
            default: return "Balanced";
        }
    }

    public LoadControl buildLoadControl() {
        int minBuffer, maxBuffer, forPlayback, afterRebuffer;
        switch (effectiveProfile()) {
            case Prefs.PROFILE_ANTI_BUFFER:
                minBuffer = 45_000;
                maxBuffer = 180_000;
                forPlayback = 4_000;
                afterRebuffer = 8_000;
                break;
            case Prefs.PROFILE_LOW_LATENCY:
                minBuffer = 8_000;
                maxBuffer = 30_000;
                forPlayback = 1_000;
                afterRebuffer = 2_000;
                break;
            default: // balanced
                minBuffer = 20_000;
                maxBuffer = 90_000;
                forPlayback = 2_500;
                afterRebuffer = 5_000;
                break;
        }

        // Each rebuffer pushes the safety margin up (bounded).
        int extra = Math.min(rebufferCount, 4) * 6_000;
        minBuffer += extra;
        maxBuffer += extra * 2;
        afterRebuffer += Math.min(rebufferCount, 4) * 1_000;

        // Contract: forPlayback <= minBuffer, afterRebuffer <= minBuffer, minBuffer <= maxBuffer
        forPlayback = Math.min(forPlayback, minBuffer);
        afterRebuffer = Math.min(afterRebuffer, minBuffer);
        maxBuffer = Math.max(maxBuffer, minBuffer);

        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(minBuffer, maxBuffer, forPlayback, afterRebuffer)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(30_000, true)
                .setTargetBufferBytes(C_TARGET_BUFFER_BYTES)
                .build();
    }

    /** -1 lets ExoPlayer size the byte buffer from the selected track bitrate. */
    private static final int C_TARGET_BUFFER_BYTES = -1;

    /**
     * How far behind the true live edge playback should deliberately sit, for HLS
     * live channels. This is the cushion: as long as the player stays this far back,
     * a throughput dip drains the cushion instead of draining the screen, so nothing
     * visible happens. Deeper on a shaky line, tighter when latency matters more.
     */
    public long liveTargetOffsetMs() {
        switch (effectiveProfile()) {
            case Prefs.PROFILE_ANTI_BUFFER: return 14_000;
            case Prefs.PROFILE_LOW_LATENCY: return 3_000;
            default: return 7_000;
        }
    }

    /** How close to the live edge ExoPlayer is allowed to catch up to (speeds up within this). */
    public long liveMinOffsetMs() {
        return Math.max(2_000, liveTargetOffsetMs() - 4_000);
    }

    /** How far back ExoPlayer is allowed to fall before it has to choose between slowing down and stalling. */
    public long liveMaxOffsetMs() {
        return liveTargetOffsetMs() + 10_000;
    }

    // ------------------------------------------------------------------
    // Bitrate ceiling
    // ------------------------------------------------------------------

    /** Bits per second we are willing to spend on video, given the line quality. */
    public int maxVideoBitrate() {
        int userCap = prefs.getMaxBitrateKbps();
        if (userCap > 0) return userCap * 1000;
        if (!prefs.isAdaptiveEnabled()) return Integer.MAX_VALUE;

        long bps = monitor.bitsPerSecond();
        // Never plan to use more than 72% of measured throughput: leaves room for
        // audio, TCP overhead, and the rest of the household.
        long budget = (long) (bps * 0.72f * penalty);

        switch (monitor.tier()) {
            case NetworkMonitor.TIER_VERY_LOW:
                budget = Math.min(budget, 1_100_000L);
                break;
            case NetworkMonitor.TIER_LOW:
                budget = Math.min(budget, 2_800_000L);
                break;
            case NetworkMonitor.TIER_MEDIUM:
                budget = Math.min(budget, 6_000_000L);
                break;
            case NetworkMonitor.TIER_HIGH:
                budget = Math.min(budget, 16_000_000L);
                break;
            default:
                break;
        }
        if (budget < 350_000L) budget = 350_000L;
        return (int) Math.min(budget, (long) Integer.MAX_VALUE);
    }

    public DefaultTrackSelector buildTrackSelector() {
        // Conservative fraction + long "increase" window = fewer quality flip-flops.
        AdaptiveTrackSelection.Factory adaptive = new AdaptiveTrackSelection.Factory(
                /* minDurationForQualityIncreaseMs= */ 12_000,
                /* maxDurationForQualityDecreaseMs= */ 20_000,
                /* minDurationToRetainAfterDiscardMs= */ 20_000,
                /* bandwidthFraction= */ 0.70f);

        DefaultTrackSelector selector = new DefaultTrackSelector(ctx, adaptive);
        selector.setParameters(applyConstraints(selector.buildUponParameters()).build());
        return selector;
    }

    public DefaultTrackSelector.Parameters.Builder applyConstraints(
            DefaultTrackSelector.Parameters.Builder b) {
        int cap = maxVideoBitrate();
        b.setMaxVideoBitrate(cap);
        b.setForceLowestBitrate(false);
        b.setExceedVideoConstraintsIfNecessary(true);
        b.setExceedRendererCapabilitiesIfNecessary(true);
        b.setAllowVideoMixedMimeTypeAdaptiveness(true);
        b.setAllowAudioMixedMimeTypeAdaptiveness(true);

        int tier = monitor.tier();
        if (tier == NetworkMonitor.TIER_VERY_LOW) {
            b.setMaxVideoSize(854, 480);
        } else if (tier == NetworkMonitor.TIER_LOW) {
            b.setMaxVideoSize(1280, 720);
        } else {
            b.clearVideoSizeConstraints();
        }
        return b;
    }

    /** Applies the current ceiling to a live player without recreating it. */
    public void retune(androidx.media3.exoplayer.ExoPlayer player) {
        if (player == null) return;
        try {
            TrackSelectionParameters.Builder tsb = player.getTrackSelectionParameters().buildUpon();
            tsb.setMaxVideoBitrate(maxVideoBitrate());
            int tier = monitor.tier();
            if (tier == NetworkMonitor.TIER_VERY_LOW) {
                tsb.setMaxVideoSize(854, 480);
            } else if (tier == NetworkMonitor.TIER_LOW) {
                tsb.setMaxVideoSize(1280, 720);
            } else {
                tsb.clearVideoSizeConstraints();
            }
            player.setTrackSelectionParameters(tsb.build());
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Feedback from the player
    // ------------------------------------------------------------------

    public void onRebuffer() {
        rebufferCount++;
        lastRebufferAt = System.currentTimeMillis();
        penalty = Math.max(0.35f, penalty * 0.70f);
    }

    /** Called on a timer while playback is healthy. Returns true if the ceiling moved. */
    public boolean onHealthyTick() {
        long now = System.currentTimeMillis();
        if (penalty >= 1.0f) return false;
        if (now - lastRebufferAt < 45_000L) return false;
        if (now - lastRecoveryAt < 30_000L) return false;
        lastRecoveryAt = now;
        penalty = Math.min(1.0f, penalty * 1.25f);
        return true;
    }

    public void resetForNewStream() {
        penalty = 1.0f;
        rebufferCount = 0;
        lastRebufferAt = 0;
    }

    public int rebufferCount() {
        return rebufferCount;
    }

    public float penalty() {
        return penalty;
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    /** Timeouts scale with line quality: a slow line needs patience, not failure. */
    public DataSource.Factory buildDataSourceFactory(String userAgent) {
        int tier = monitor.tier();
        int connect = tier <= NetworkMonitor.TIER_LOW ? 20_000 : 12_000;
        int read = tier <= NetworkMonitor.TIER_LOW ? 30_000 : 18_000;

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent == null || userAgent.isEmpty()
                        ? com.wm.streamhub.util.Http.DEFAULT_UA : userAgent)
                .setConnectTimeoutMs(connect)
                .setReadTimeoutMs(read)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true);

        return new DefaultDataSource.Factory(ctx, http);
    }

    /**
     * On a healthy line we ask the panel for HLS (.m3u8) so the server can hand us
     * multiple renditions and ExoPlayer can switch between them. On a weak or
     * flaky line we prefer the plain TS endpoint, which starts faster and has
     * fewer round trips.
     */
    public boolean preferHls(boolean serverPrefersHls) {
        if (!serverPrefersHls) return false;
        if (!prefs.isAdaptiveEnabled()) return serverPrefersHls;
        return monitor.tier() >= NetworkMonitor.TIER_MEDIUM;
    }

    public String statusLine() {
        return NetworkMonitor.tierName(monitor.tier())
                + " · " + monitor.speedLabel()
                + " · " + profileName()
                + " · cap " + (maxVideoBitrate() / 1000) + "k";
    }
}

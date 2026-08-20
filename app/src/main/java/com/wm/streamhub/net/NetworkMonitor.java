package com.wm.streamhub.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import com.wm.streamhub.util.Http;
import com.wm.streamhub.util.Prefs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * Tracks how fast the user's internet actually is, right now.
 *
 * Three inputs feed the estimate:
 *   1. ExoPlayer's own DefaultBandwidthMeter (real throughput of the stream being played)
 *   2. A short HTTP range-probe against the user's own server (used before playback starts)
 *   3. The last known good estimate, persisted between launches
 *
 * Everything downstream (buffer sizes, bitrate ceiling, timeouts, HLS vs TS choice)
 * is derived from the tier this class reports.
 */
@UnstableApi
public class NetworkMonitor {

    public static final int TIER_OFFLINE = -1;
    public static final int TIER_VERY_LOW = 0;   // < 1.5 Mbps  - SD only, huge buffer
    public static final int TIER_LOW = 1;        // < 4 Mbps    - 720p cap
    public static final int TIER_MEDIUM = 2;     // < 9 Mbps    - 1080p cap
    public static final int TIER_HIGH = 3;       // < 25 Mbps   - full 1080p, comfortable
    public static final int TIER_VERY_HIGH = 4;  // >= 25 Mbps  - anything, incl. 4K

    private static NetworkMonitor instance;

    private final Context ctx;
    private final Prefs prefs;
    private final DefaultBandwidthMeter bandwidthMeter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();

    private long probedBps = 0;
    private long lastProbeAt = 0;

    public interface Listener {
        void onNetworkChanged(int tier, long bitsPerSecond);
    }

    private NetworkMonitor(Context context) {
        this.ctx = context.getApplicationContext();
        this.prefs = new Prefs(this.ctx);
        long seed = prefs.getLastBandwidthBps();
        DefaultBandwidthMeter.Builder b = new DefaultBandwidthMeter.Builder(this.ctx);
        if (seed > 0) {
            b.setInitialBitrateEstimate(seed);
        }
        // Slightly slower to react up, fast to react down: keeps a weak line stable.
        b.setSlidingWindowMaxWeight(2500);
        b.setResetOnNetworkTypeChange(true);
        this.bandwidthMeter = b.build();
    }

    public static synchronized NetworkMonitor get(Context c) {
        if (instance == null) instance = new NetworkMonitor(c);
        return instance;
    }

    public DefaultBandwidthMeter meter() {
        return bandwidthMeter;
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Best available estimate of downstream throughput, in bits per second. */
    public long bitsPerSecond() {
        long fromPlayer = bandwidthMeter.getBitrateEstimate();
        long best = Math.max(fromPlayer, probedBps);
        if (best <= 0) best = prefs.getLastBandwidthBps();
        if (best <= 0) best = 5_000_000L; // neutral first guess: 5 Mbps
        return best;
    }

    public int tier() {
        if (!isOnline()) return TIER_OFFLINE;
        long bps = bitsPerSecond();
        if (bps < 1_500_000L) return TIER_VERY_LOW;
        if (bps < 4_000_000L) return TIER_LOW;
        if (bps < 9_000_000L) return TIER_MEDIUM;
        if (bps < 25_000_000L) return TIER_HIGH;
        return TIER_VERY_HIGH;
    }

    public static String tierName(int tier) {
        switch (tier) {
            case TIER_OFFLINE: return "Offline";
            case TIER_VERY_LOW: return "Very slow";
            case TIER_LOW: return "Slow";
            case TIER_MEDIUM: return "Good";
            case TIER_HIGH: return "Fast";
            default: return "Very fast";
        }
    }

    public String speedLabel() {
        long bps = bitsPerSecond();
        double mbps = bps / 1_000_000.0;
        if (mbps >= 10) return String.format(java.util.Locale.US, "%.0f Mbps", mbps);
        return String.format(java.util.Locale.US, "%.1f Mbps", mbps);
    }

    public String connectionLabel() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "Unknown";
            if (Build.VERSION.SDK_INT >= 23) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (caps == null) return "Offline";
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Mobile";
                return "Connected";
            }
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return "Offline";
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) return "Ethernet";
            if (ni.getType() == ConnectivityManager.TYPE_WIFI) return "Wi-Fi";
            return "Mobile";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    public boolean isOnline() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            if (Build.VERSION.SDK_INT >= 23) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Downloads ~700 KB from the given URL to measure real throughput before the
     * first stream starts. Runs off the main thread; safe to call often (rate limited).
     */
    public void probe(final String url) {
        if (url == null || url.isEmpty()) return;
        if (System.currentTimeMillis() - lastProbeAt < 45_000L) return;
        lastProbeAt = System.currentTimeMillis();
        io.execute(new Runnable() {
            @Override
            public void run() {
                long[] r = Http.probe(url, 700 * 1024, 6000, 8000);
                applyProbeResult(r, null);
            }
        });
    }

    public interface ProbeCallback {
        /** bps <= 0 means the probe failed (timed out / server unreachable). */
        void onResult(long bps);
    }

    /**
     * A user-requested, on-demand test — used by Settings' "Run a speed test".
     * Unlike {@link #probe}, this ignores the 45s rate limit (the customer just
     * pressed a button, they want a fresh number) and pulls a bigger 3 MB sample
     * so a fast line has room to ramp past TCP slow-start before we measure it.
     * This is always a test of the ACTIVE PROVIDER'S SERVER, not the customer's
     * general internet connection — a fast home connection can still measure
     * low here if that specific server or the route to it is the bottleneck.
     */
    public void testNow(final String url, final ProbeCallback cb) {
        if (url == null || url.isEmpty()) {
            if (cb != null) main.post(() -> cb.onResult(0));
            return;
        }
        lastProbeAt = System.currentTimeMillis();
        io.execute(new Runnable() {
            @Override
            public void run() {
                long[] r = Http.probe(url, 3 * 1024 * 1024, 8000, 15000);
                final long bps = applyProbeResult(r, null);
                if (cb != null) main.post(() -> cb.onResult(bps));
            }
        });
    }

    /** Shared result-handling for both probe() and testNow(). Returns the measured bps, or 0. */
    private long applyProbeResult(long[] r, Void unused) {
        long bytes = r[0];
        long ms = r[1];
        if (bytes > 64 * 1024 && ms > 40) {
            long bps = (bytes * 8L * 1000L) / ms;
            // Cap absurd LAN-cached numbers so the ceiling logic stays sane.
            probedBps = Math.min(bps, 200_000_000L);
            prefs.setLastBandwidthBps(probedBps);
            notifyChanged();
            return probedBps;
        }
        return 0;
    }

    /** Persist the current estimate so the next launch starts smart. */
    public void persist() {
        long bps = bandwidthMeter.getBitrateEstimate();
        if (bps > 0) prefs.setLastBandwidthBps(bps);
    }

    private void notifyChanged() {
        main.post(new Runnable() {
            @Override
            public void run() {
                int t = tier();
                long bps = bitsPerSecond();
                for (int i = listeners.size() - 1; i >= 0; i--) {
                    try {
                        listeners.get(i).onNetworkChanged(t, bps);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }
}

package com.wm.streamhub.data;

import android.util.Xml;

import com.wm.streamhub.util.Http;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Fetches and parses a server's XMLTV feed, if the reseller set one on the
 * server profile (Servers -> edit -> "EPG URL"). That field already existed
 * in ServerProfile but nothing ever read it -- the Guide screen always showed
 * the static "no guide data yet" placeholder regardless. This is what makes
 * it real: real programme titles and times, matched to channels by the
 * XMLTV <channel id> attribute (same id the panel reports as epgChannelId).
 *
 * Parsed with a streaming XmlPullParser rather than loading the whole feed
 * into a String -- XMLTV files from real panels can run tens of MBs.
 */
public class EpgLoader {

    public static class Programme {
        public long startMs;
        public long stopMs;
        public String title = "";
    }

    /** XMLTV channel id -> programmes, sorted by start time. */
    private final Map<String, List<Programme>> byChannel = new HashMap<>();

    public static EpgLoader fetch(String url, String userAgent) throws Exception {
        EpgLoader out = new EpgLoader();
        BufferedReader reader = Http.openReader(url, 8000, 25000, userAgent);
        try {
            XmlPullParser xpp = Xml.newPullParser();
            xpp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            xpp.setInput(reader);

            boolean inProgramme = false;
            boolean inTitle = false;
            String curChannel = null;
            long curStart = 0, curStop = 0;
            StringBuilder curTitle = null;

            int eventType = xpp.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                String name = xpp.getName();
                if (eventType == XmlPullParser.START_TAG) {
                    if ("programme".equalsIgnoreCase(name)) {
                        inProgramme = true;
                        curChannel = xpp.getAttributeValue(null, "channel");
                        curStart = parseTime(xpp.getAttributeValue(null, "start"));
                        curStop = parseTime(xpp.getAttributeValue(null, "stop"));
                        curTitle = new StringBuilder();
                    } else if (inProgramme && "title".equalsIgnoreCase(name)) {
                        inTitle = true;
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    if (inTitle && curTitle != null) curTitle.append(xpp.getText());
                } else if (eventType == XmlPullParser.END_TAG) {
                    if ("title".equalsIgnoreCase(name)) {
                        inTitle = false;
                    } else if ("programme".equalsIgnoreCase(name)) {
                        inProgramme = false;
                        if (curChannel != null && curStart > 0 && curStop > curStart) {
                            Programme p = new Programme();
                            p.startMs = curStart;
                            p.stopMs = curStop;
                            p.title = curTitle == null ? "" : curTitle.toString().trim();
                            List<Programme> list = out.byChannel.get(curChannel);
                            if (list == null) {
                                list = new ArrayList<>();
                                out.byChannel.put(curChannel, list);
                            }
                            list.add(p);
                        }
                        curChannel = null;
                        curTitle = null;
                    }
                }
                eventType = xpp.next();
            }
        } finally {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
        }

        for (List<Programme> list : out.byChannel.values()) {
            Collections.sort(list, new Comparator<Programme>() {
                @Override
                public int compare(Programme a, Programme b) {
                    return Long.compare(a.startMs, b.startMs);
                }
            });
        }
        return out;
    }

    /** XMLTV times look like "20260819193000 +0000" (or " -0500", etc). */
    private static long parseTime(String raw) {
        if (raw == null || raw.trim().length() < 14) return 0;
        raw = raw.trim();
        try {
            String core = raw.substring(0, 14);
            String zone = raw.length() > 15 ? raw.substring(15).trim() : "";
            if (zone.isEmpty()) zone = "+0000";
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT" + zone));
            return f.parse(core).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    public Programme now(String channelId) {
        if (channelId == null || channelId.isEmpty()) return null;
        List<Programme> list = byChannel.get(channelId);
        if (list == null) return null;
        long t = System.currentTimeMillis();
        for (Programme p : list) {
            if (t >= p.startMs && t < p.stopMs) return p;
        }
        return null;
    }

    public Programme next(String channelId) {
        if (channelId == null || channelId.isEmpty()) return null;
        List<Programme> list = byChannel.get(channelId);
        if (list == null) return null;
        long t = System.currentTimeMillis();
        for (Programme p : list) {
            if (p.startMs > t) return p;
        }
        return null;
    }

    public boolean hasChannel(String channelId) {
        return channelId != null && byChannel.containsKey(channelId);
    }
}

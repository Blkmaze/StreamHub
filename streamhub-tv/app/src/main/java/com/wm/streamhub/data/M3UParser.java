package com.wm.streamhub.data;

import com.wm.streamhub.model.ServerProfile;
import com.wm.streamhub.model.StreamItem;
import com.wm.streamhub.util.Http;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Streaming parser for extended M3U playlists. Handles very large files without OOM. */
public class M3UParser {

    private static final int MAX_ENTRIES = 60000;

    public static List<StreamItem> parse(ServerProfile server) throws IOException {
        List<StreamItem> out = new ArrayList<>();
        BufferedReader r = null;
        try {
            r = Http.openReader(server.m3uUrl.trim(), 15000, 30000, server.userAgent);
            String line;
            String pendingName = null;
            String pendingLogo = "";
            String pendingGroup = "";
            String pendingTvgId = "";
            int index = 0;

            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("#EXTINF")) {
                    pendingLogo = attr(line, "tvg-logo");
                    pendingGroup = attr(line, "group-title");
                    pendingTvgId = attr(line, "tvg-id");
                    int comma = line.lastIndexOf(',');
                    pendingName = comma >= 0 && comma + 1 < line.length()
                            ? line.substring(comma + 1).trim() : "Unnamed";
                    if (pendingName.isEmpty()) {
                        String alt = attr(line, "tvg-name");
                        pendingName = alt.isEmpty() ? "Unnamed" : alt;
                    }
                } else if (line.startsWith("#")) {
                    // EXTGRP overrides group-title when present
                    if (line.startsWith("#EXTGRP:")) {
                        pendingGroup = line.substring(8).trim();
                    }
                } else if (pendingName != null) {
                    StreamItem s = new StreamItem();
                    s.kind = guessKind(pendingGroup, line);
                    s.serverId = server.id;
                    s.serverName = server.label();
                    s.name = pendingName;
                    s.logo = pendingLogo;
                    s.epgChannelId = pendingTvgId;
                    s.categoryName = pendingGroup.isEmpty() ? "Ungrouped" : pendingGroup;
                    s.categoryId = s.categoryName;
                    s.directUrl = line;
                    s.id = String.valueOf(++index);
                    s.number = index;
                    out.add(s);
                    pendingName = null;
                    pendingLogo = "";
                    pendingGroup = "";
                    pendingTvgId = "";
                    if (out.size() >= MAX_ENTRIES) break;
                }
            }
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (out.isEmpty()) {
            throw new IOException("Playlist had no channels (is the URL correct?)");
        }
        return out;
    }

    private static int guessKind(String group, String url) {
        String g = group.toLowerCase(java.util.Locale.US);
        String u = url.toLowerCase(java.util.Locale.US);
        if (u.contains("/series/") || g.contains("series") || g.contains("show")) {
            return StreamItem.KIND_SERIES;
        }
        if (u.contains("/movie/") || u.endsWith(".mp4") || u.endsWith(".mkv")
                || u.endsWith(".avi") || g.contains("vod") || g.contains("movie")
                || g.contains("film")) {
            return StreamItem.KIND_VOD;
        }
        return StreamItem.KIND_LIVE;
    }

    private static String attr(String line, String key) {
        String needle = key + "=\"";
        int i = line.indexOf(needle);
        if (i < 0) return "";
        int start = i + needle.length();
        int end = line.indexOf('"', start);
        if (end < 0) return "";
        return line.substring(start, end).trim();
    }
}

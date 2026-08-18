package com.wm.streamhub.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/** Minimal HTTP helper. All calls must run off the main thread. */
public final class Http {

    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 9; AFTT Build/PS7233) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Version/4.0 Chrome/100.0 Safari/537.36 StreamHubTV/1.0";

    private Http() {
    }

    public static String get(String url, int connectMs, int readMs) throws IOException {
        return get(url, connectMs, readMs, DEFAULT_UA);
    }

    public static String get(String url, int connectMs, int readMs, String userAgent) throws IOException {
        HttpURLConnection c = open(url, connectMs, readMs, userAgent);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("Empty response (HTTP " + code + ")");
            in = maybeGzip(c, in);
            String body = readAll(in);
            if (code >= 400) throw new IOException("HTTP " + code + ": " + trim(body, 180));
            return body;
        } finally {
            c.disconnect();
        }
    }

    /** Downloads up to maxBytes and reports elapsed time; used for the speed probe. */
    public static long[] probe(String url, int maxBytes, int connectMs, int readMs) {
        HttpURLConnection c = null;
        long start = System.nanoTime();
        long read = 0;
        try {
            c = open(url, connectMs, readMs, DEFAULT_UA);
            c.setRequestProperty("Range", "bytes=0-" + Math.max(1, maxBytes - 1));
            InputStream in = c.getInputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while (read < maxBytes && (n = in.read(buf)) > 0) {
                read += n;
            }
        } catch (Exception ignored) {
            // partial data still gives us a usable sample
        } finally {
            if (c != null) c.disconnect();
        }
        long elapsedMs = (System.nanoTime() - start) / 1000000L;
        return new long[]{read, Math.max(1, elapsedMs)};
    }

    public static String post(String url, String contentType, String body,
                              String[] headers, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = open(url, connectMs, readMs, DEFAULT_UA);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            if (headers != null) {
                for (int i = 0; i + 1 < headers.length; i += 2) {
                    c.setRequestProperty(headers[i], headers[i + 1]);
                }
            }
            byte[] payload = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(payload.length);
            c.getOutputStream().write(payload);
            c.getOutputStream().flush();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String out = in == null ? "" : readAll(maybeGzip(c, in));
            if (code >= 400) throw new IOException("HTTP " + code + ": " + trim(out, 180));
            return out;
        } finally {
            c.disconnect();
        }
    }

    public static String getWithHeaders(String url, String[] headers, int connectMs, int readMs)
            throws IOException {
        HttpURLConnection c = open(url, connectMs, readMs, DEFAULT_UA);
        try {
            if (headers != null) {
                for (int i = 0; i + 1 < headers.length; i += 2) {
                    c.setRequestProperty(headers[i], headers[i + 1]);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String out = in == null ? "" : readAll(maybeGzip(c, in));
            if (code >= 400) throw new IOException("HTTP " + code + ": " + trim(out, 180));
            return out;
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, int connectMs, int readMs, String ua)
            throws IOException {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", ua == null || ua.isEmpty() ? DEFAULT_UA : ua);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.setRequestProperty("Connection", "close");
        return c;
    }

    private static InputStream maybeGzip(HttpURLConnection c, InputStream in) throws IOException {
        if (in != null && "gzip".equalsIgnoreCase(c.getContentEncoding())) {
            return new GZIPInputStream(in);
        }
        return in;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    /** Streams a text body line by line (used for very large M3U playlists). */
    public static BufferedReader openReader(String url, int connectMs, int readMs, String ua)
            throws IOException {
        HttpURLConnection c = open(url, connectMs, readMs, ua);
        InputStream in = maybeGzip(c, c.getInputStream());
        return new BufferedReader(new InputStreamReader(in, "UTF-8"), 32 * 1024);
    }

    public static String trim(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }
}

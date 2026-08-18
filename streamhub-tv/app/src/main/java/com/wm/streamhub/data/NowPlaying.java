package com.wm.streamhub.data;

import com.wm.streamhub.model.StreamItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Hands the current channel list to the player without pushing thousands of rows
 * through an Intent (which would blow the Binder transaction limit).
 */
public final class NowPlaying {

    private static final List<StreamItem> queue = new ArrayList<>();
    private static int index = 0;
    private static String title = "";

    private NowPlaying() {
    }

    public static void set(List<StreamItem> items, int startIndex, String listTitle) {
        queue.clear();
        if (items != null) queue.addAll(items);
        index = Math.max(0, Math.min(startIndex, Math.max(0, queue.size() - 1)));
        title = listTitle == null ? "" : listTitle;
    }

    public static List<StreamItem> queue() {
        return queue;
    }

    public static int index() {
        return index;
    }

    public static void setIndex(int i) {
        if (queue.isEmpty()) {
            index = 0;
            return;
        }
        index = ((i % queue.size()) + queue.size()) % queue.size();
    }

    public static StreamItem current() {
        if (queue.isEmpty()) return null;
        if (index < 0 || index >= queue.size()) index = 0;
        return queue.get(index);
    }

    public static StreamItem step(int delta) {
        if (queue.isEmpty()) return null;
        setIndex(index + delta);
        return queue.get(index);
    }

    public static String title() {
        return title;
    }

    public static boolean isEmpty() {
        return queue.isEmpty();
    }
}

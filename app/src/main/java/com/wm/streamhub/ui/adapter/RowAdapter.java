package com.wm.streamhub.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wm.streamhub.R;

import java.util.ArrayList;
import java.util.List;

/**
 * One adapter for every list in the app. Rows are plain data; the caller decides
 * what a click means. Handles D-pad focus reporting and the "currently open" marker.
 */
public class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {

    public static class Row {
        public String title;
        public String subtitle;
        public String badge;
        public String icon;
        public Object tag;

        public Row(String title) {
            this.title = title;
        }

        public Row(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }

        public Row sub(String s) {
            this.subtitle = s;
            return this;
        }

        public Row badge(String s) {
            this.badge = s;
            return this;
        }

        public Row icon(String s) {
            this.icon = s;
            return this;
        }

        public Row tag(Object o) {
            this.tag = o;
            return this;
        }
    }

    public interface OnClick {
        void onClick(int position, Row row);
    }

    public interface OnFocus {
        void onFocus(int position, Row row);
    }

    public interface OnLongClick {
        boolean onLongClick(int position, Row row);
    }

    private final List<Row> rows = new ArrayList<>();
    private OnClick onClick;
    private OnFocus onFocus;
    private OnLongClick onLongClick;
    private int activated = -1;

    public RowAdapter setOnClick(OnClick c) {
        this.onClick = c;
        return this;
    }

    public RowAdapter setOnFocus(OnFocus f) {
        this.onFocus = f;
        return this;
    }

    public RowAdapter setOnLongClick(OnLongClick l) {
        this.onLongClick = l;
        return this;
    }

    public void submit(List<Row> newRows) {
        rows.clear();
        if (newRows != null) rows.addAll(newRows);
        if (activated >= rows.size()) activated = -1;
        notifyDataSetChanged();
    }

    public List<Row> rows() {
        return rows;
    }

    public Row get(int i) {
        return i >= 0 && i < rows.size() ? rows.get(i) : null;
    }

    public int size() {
        return rows.size();
    }

    public void setActivated(int index) {
        int old = activated;
        activated = index;
        if (old >= 0) notifyItemChanged(old);
        if (index >= 0) notifyItemChanged(index);
    }

    public int activated() {
        return activated;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull final VH h, int position) {
        final Row r = rows.get(position);
        h.title.setText(r.title == null ? "" : r.title);

        if (r.subtitle == null || r.subtitle.isEmpty()) {
            h.subtitle.setVisibility(View.GONE);
        } else {
            h.subtitle.setVisibility(View.VISIBLE);
            h.subtitle.setText(r.subtitle);
        }
        if (r.badge == null || r.badge.isEmpty()) {
            h.badge.setVisibility(View.GONE);
        } else {
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setText(r.badge);
        }
        if (r.icon == null || r.icon.isEmpty()) {
            h.icon.setVisibility(View.GONE);
        } else {
            h.icon.setVisibility(View.VISIBLE);
            h.icon.setText(r.icon);
        }

        h.root.setActivated(position == activated);

        h.root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int p = h.getAdapterPosition();
                if (p != RecyclerView.NO_POSITION && onClick != null) onClick.onClick(p, rows.get(p));
            }
        });

        h.root.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                int p = h.getAdapterPosition();
                if (p != RecyclerView.NO_POSITION && onLongClick != null) {
                    return onLongClick.onLongClick(p, rows.get(p));
                }
                return false;
            }
        });

        h.root.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) return;
                int p = h.getAdapterPosition();
                if (p != RecyclerView.NO_POSITION && onFocus != null) onFocus.onFocus(p, rows.get(p));
            }
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final LinearLayout root;
        final TextView title;
        final TextView subtitle;
        final TextView badge;
        final TextView icon;

        VH(View v) {
            super(v);
            root = v.findViewById(R.id.rowRoot);
            title = v.findViewById(R.id.rowTitle);
            subtitle = v.findViewById(R.id.rowSubtitle);
            badge = v.findViewById(R.id.rowBadge);
            icon = v.findViewById(R.id.rowIcon);
        }
    }

    // ------------------------------------------------------------------

    /** Wires a RecyclerView with a vertical layout manager and this adapter. */
    public static RowAdapter attach(RecyclerView rv) {
        RowAdapter a = new RowAdapter();
        rv.setLayoutManager(new LinearLayoutManager(rv.getContext()));
        rv.setHasFixedSize(false);
        rv.setItemAnimator(null);
        rv.setAdapter(a);
        return a;
    }

    /** Moves D-pad focus onto a row, scrolling it into view first. */
    public static void focusPosition(final RecyclerView rv, final int position) {
        if (rv == null) return;
        rv.post(new Runnable() {
            @Override
            public void run() {
                RecyclerView.LayoutManager lm = rv.getLayoutManager();
                if (lm == null) return;
                if (rv.getAdapter() == null || rv.getAdapter().getItemCount() == 0) return;
                int p = Math.max(0, Math.min(position, rv.getAdapter().getItemCount() - 1));
                View v = lm.findViewByPosition(p);
                if (v != null) {
                    v.requestFocus();
                } else {
                    rv.scrollToPosition(p);
                    rv.post(new Runnable() {
                        @Override
                        public void run() {
                            RecyclerView.LayoutManager lm2 = rv.getLayoutManager();
                            if (lm2 == null) return;
                            View v2 = lm2.findViewByPosition(
                                    Math.max(0, Math.min(position,
                                            rv.getAdapter().getItemCount() - 1)));
                            if (v2 != null) v2.requestFocus();
                        }
                    });
                }
            }
        });
    }
}

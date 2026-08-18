package com.wm.streamhub.ui.adapter;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wm.streamhub.R;
import com.wm.streamhub.model.ChatMessage;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {

    private final List<ChatMessage> items = new ArrayList<>();
    private final SimpleDateFormat fmt = new SimpleDateFormat("MMM d · HH:mm", Locale.US);

    public void submit(List<ChatMessage> msgs) {
        items.clear();
        if (msgs != null) items.addAll(msgs);
        notifyDataSetChanged();
    }

    public int size() {
        return items.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ChatMessage m = items.get(position);
        h.body.setText(m.body);

        String meta = fmt.format(new Date(m.createdAt));
        if (m.pending) meta = "sending…";
        else if (m.failed) meta = "not delivered · will retry when you send again";
        h.meta.setText(meta);

        boolean mine = m.isMine();
        h.bubble.setBackgroundResource(mine ? R.drawable.bubble_out : R.drawable.bubble_in);
        LinearLayout parent = (LinearLayout) h.itemView;
        parent.setGravity(mine ? Gravity.END : Gravity.START);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View bubble;
        final TextView body;
        final TextView meta;

        VH(View v) {
            super(v);
            bubble = v.findViewById(R.id.bubble);
            body = v.findViewById(R.id.msgBody);
            meta = v.findViewById(R.id.msgMeta);
        }
    }
}

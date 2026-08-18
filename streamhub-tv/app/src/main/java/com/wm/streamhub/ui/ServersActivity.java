package com.wm.streamhub.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.wm.streamhub.R;
import com.wm.streamhub.data.ContentRepository;
import com.wm.streamhub.model.ServerProfile;
import com.wm.streamhub.ui.adapter.RowAdapter;
import com.wm.streamhub.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/** Manage the customer's lines: add, edit, reorder, enable/disable, test. */
public class ServersActivity extends AppCompatActivity {

    private Prefs prefs;
    private ContentRepository repo;
    private RecyclerView list;
    private RowAdapter adapter;
    private TextView detailTitle, detailBody;
    private final List<ServerProfile> servers = new ArrayList<>();
    private ServerProfile selected;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_servers);

        prefs = new Prefs(this);
        repo = ContentRepository.get(this);

        list = findViewById(R.id.listServers);
        detailTitle = findViewById(R.id.detailTitle);
        detailBody = findViewById(R.id.detailBody);
        adapter = RowAdapter.attach(list);

        Button add = findViewById(R.id.btnAdd);
        Button test = findViewById(R.id.btnTest);

        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ServersActivity.this, AddServerActivity.class));
            }
        });

        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selected == null) {
                    toast("Select a server first");
                    return;
                }
                detailBody.setText("Testing " + selected.label() + "…");
                repo.testServer(selected, new ContentRepository.Callback<String>() {
                    @Override
                    public void onResult(String value) {
                        detailBody.setText(value);
                    }

                    @Override
                    public void onError(String message) {
                        detailBody.setText("Failed: " + message);
                    }
                });
            }
        });

        adapter.setOnFocus(new RowAdapter.OnFocus() {
            @Override
            public void onFocus(int position, RowAdapter.Row row) {
                if (row.tag instanceof ServerProfile) showDetail((ServerProfile) row.tag);
            }
        });

        adapter.setOnClick(new RowAdapter.OnClick() {
            @Override
            public void onClick(int position, RowAdapter.Row row) {
                if (!(row.tag instanceof ServerProfile)) return;
                Intent i = new Intent(ServersActivity.this, AddServerActivity.class);
                i.putExtra(AddServerActivity.EXTRA_ID, ((ServerProfile) row.tag).id);
                startActivity(i);
            }
        });

        adapter.setOnLongClick(new RowAdapter.OnLongClick() {
            @Override
            public boolean onLongClick(int position, RowAdapter.Row row) {
                if (!(row.tag instanceof ServerProfile)) return false;
                confirmDelete((ServerProfile) row.tag);
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        servers.clear();
        servers.addAll(prefs.getServers());
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (int i = 0; i < servers.size(); i++) {
            ServerProfile s = servers.get(i);
            RowAdapter.Row r = new RowAdapter.Row(s.label(), s.subtitle()).tag(s);
            r.icon(String.valueOf(i + 1));
            if (!s.enabled) r.badge("disabled");
            rows.add(r);
        }
        adapter.submit(rows);
        if (servers.isEmpty()) {
            detailTitle.setText("No servers yet");
            detailBody.setText("Add your first line to get started.\n\n"
                    + "Xtream Codes: host, username and password from your provider.\n"
                    + "M3U: the full playlist URL.");
        } else {
            showDetail(servers.get(0));
            RowAdapter.focusPosition(list, 0);
        }
    }

    private void showDetail(ServerProfile s) {
        selected = s;
        detailTitle.setText(s.label());
        StringBuilder sb = new StringBuilder();
        sb.append(s.isXtream() ? "Type: Xtream Codes panel" : "Type: M3U playlist").append('\n');
        if (s.isXtream()) {
            sb.append("Host: ").append(s.normalizedHost()).append('\n');
            sb.append("User: ").append(s.username).append('\n');
        } else {
            sb.append("Playlist: ").append(s.m3uUrl).append('\n');
            if (!s.epgUrl.isEmpty()) sb.append("EPG: ").append(s.epgUrl).append('\n');
        }
        sb.append("Priority: ").append(s.priority + 1)
                .append(s.priority == 0 ? "  (tried first for failover)" : "").append('\n');
        sb.append("Adaptive HLS: ").append(s.preferHls ? "preferred" : "off").append('\n');
        sb.append("Status: ").append(s.enabled ? "enabled" : "disabled").append("\n\n");
        sb.append("OK to edit · long-press to delete · ▶ / ◀ to reorder");
        detailBody.setText(sb.toString());
    }

    private void confirmDelete(final ServerProfile s) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + s.label() + "?")
                .setMessage("This removes the line from this device only.")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    prefs.deleteServer(s.id);
                    repo.clearServer(s.id);
                    reload();
                    toast("Removed");
                })
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (selected != null
                && (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT)) {
            int index = servers.indexOf(selected);
            int target = keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    ? index - 1 : index + 1;
            if (index >= 0 && target >= 0 && target < servers.size()) {
                ServerProfile other = servers.get(target);
                int p = selected.priority;
                selected.priority = other.priority;
                other.priority = p;
                prefs.upsertServer(selected);
                prefs.upsertServer(other);
                reload();
                RowAdapter.focusPosition(list, target);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

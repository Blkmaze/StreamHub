package com.wm.streamhub.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.wm.streamhub.R;
import com.wm.streamhub.data.ContentRepository;
import com.wm.streamhub.model.ServerProfile;
import com.wm.streamhub.util.Prefs;

/** Add or edit one line. Deliberately short: typing on a remote is painful. */
public class AddServerActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "serverId";

    private Prefs prefs;
    private ContentRepository repo;
    private ServerProfile editing;

    private EditText inName, inHost, inUser, inPass, inM3u, inEpg;
    private LinearLayout groupXtream, groupM3u;
    private TextView formTitle, formStatus;
    private Button typeXtream, typeM3u;
    private boolean isXtream = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_server);

        prefs = new Prefs(this);
        repo = ContentRepository.get(this);

        formTitle = findViewById(R.id.formTitle);
        formStatus = findViewById(R.id.formStatus);
        inName = findViewById(R.id.inName);
        inHost = findViewById(R.id.inHost);
        inUser = findViewById(R.id.inUser);
        inPass = findViewById(R.id.inPass);
        inM3u = findViewById(R.id.inM3u);
        inEpg = findViewById(R.id.inEpg);
        groupXtream = findViewById(R.id.groupXtream);
        groupM3u = findViewById(R.id.groupM3u);
        typeXtream = findViewById(R.id.typeXtream);
        typeM3u = findViewById(R.id.typeM3u);

        String id = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ID);
        if (id != null) editing = prefs.getServer(id);
        if (editing == null) {
            editing = new ServerProfile();
        } else {
            formTitle.setText(R.string.edit_server);
        }

        isXtream = editing.isXtream();
        inName.setText(editing.name);
        inHost.setText(editing.host);
        inUser.setText(editing.username);
        inPass.setText(editing.password);
        inM3u.setText(editing.m3uUrl);
        inEpg.setText(editing.epgUrl);
        applyType();

        typeXtream.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isXtream = true;
                applyType();
            }
        });
        typeM3u.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isXtream = false;
                applyType();
            }
        });

        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (collect()) {
                    prefs.upsertServer(editing);
                    prefs.setActiveServerId(editing.id);
                    repo.clearServer(editing.id);
                    Toast.makeText(AddServerActivity.this, "Saved", Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
        });

        findViewById(R.id.btnTest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!collect()) return;
                formStatus.setText("Testing…");
                repo.clearServer(editing.id);
                repo.testServer(editing, new ContentRepository.Callback<String>() {
                    @Override
                    public void onResult(String value) {
                        formStatus.setTextColor(getResources().getColor(R.color.good));
                        formStatus.setText(value);
                    }

                    @Override
                    public void onError(String message) {
                        formStatus.setTextColor(getResources().getColor(R.color.bad));
                        formStatus.setText(message);
                    }
                });
            }
        });

        findViewById(R.id.btnDelete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.deleteServer(editing.id);
                repo.clearServer(editing.id);
                Toast.makeText(AddServerActivity.this, "Deleted", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void applyType() {
        groupXtream.setVisibility(isXtream ? View.VISIBLE : View.GONE);
        groupM3u.setVisibility(isXtream ? View.GONE : View.VISIBLE);
        typeXtream.setAlpha(isXtream ? 1f : 0.55f);
        typeM3u.setAlpha(isXtream ? 0.55f : 1f);
    }

    private boolean collect() {
        editing.type = isXtream ? ServerProfile.TYPE_XTREAM : ServerProfile.TYPE_M3U;
        editing.name = inName.getText().toString().trim();
        editing.host = inHost.getText().toString().trim();
        editing.username = inUser.getText().toString().trim();
        editing.password = inPass.getText().toString().trim();
        editing.m3uUrl = inM3u.getText().toString().trim();
        editing.epgUrl = inEpg.getText().toString().trim();

        if (!editing.isValid()) {
            formStatus.setTextColor(getResources().getColor(R.color.bad));
            formStatus.setText(isXtream
                    ? "Enter at least the host and username."
                    : "Enter the full playlist URL.");
            return false;
        }
        formStatus.setTextColor(getResources().getColor(R.color.text_secondary));
        return true;
    }
}

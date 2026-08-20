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
import com.wm.streamhub.data.XtreamClient;
import com.wm.streamhub.model.ServerProfile;
import com.wm.streamhub.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/** Add or edit one line. Deliberately short: typing on a remote is painful. */
public class AddServerActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "serverId";
    /** When true, the host field is shown but locked — the DNS was baked in at
     *  build time and the customer only needs to type username/password. */
    public static final String EXTRA_LOCK_HOST = "lockHost";
    /** When true, there's no single provider picked yet — this screen just asks
     *  for username/password and tries them against every preloaded provider,
     *  landing on whichever one accepts them. No host field shown at all. */
    public static final String EXTRA_AUTODETECT = "autoDetect";

    private Prefs prefs;
    private ContentRepository repo;
    private ServerProfile editing;

    private EditText inName, inHost, inUser, inPass, inM3u, inEpg;
    private LinearLayout groupXtream, groupM3u, typeRow;
    private TextView formTitle, formSub, formStatus, lblHost;
    private Button typeXtream, typeM3u, btnSave, btnTest, btnDelete;
    private boolean isXtream = true;
    private boolean lockHost = false;
    private boolean autoDetect = false;
    private volatile boolean detecting = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_server);

        prefs = new Prefs(this);
        repo = ContentRepository.get(this);

        formTitle = findViewById(R.id.formTitle);
        formSub = findViewById(R.id.formSub);
        formStatus = findViewById(R.id.formStatus);
        lblHost = findViewById(R.id.lblHost);
        inName = findViewById(R.id.inName);
        inHost = findViewById(R.id.inHost);
        inUser = findViewById(R.id.inUser);
        inPass = findViewById(R.id.inPass);
        inM3u = findViewById(R.id.inM3u);
        inEpg = findViewById(R.id.inEpg);
        groupXtream = findViewById(R.id.groupXtream);
        groupM3u = findViewById(R.id.groupM3u);
        typeRow = findViewById(R.id.typeRow);
        typeXtream = findViewById(R.id.typeXtream);
        typeM3u = findViewById(R.id.typeM3u);
        btnSave = findViewById(R.id.btnSave);
        btnTest = findViewById(R.id.btnTest);
        btnDelete = findViewById(R.id.btnDelete);

        String id = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ID);
        lockHost = getIntent() != null && getIntent().getBooleanExtra(EXTRA_LOCK_HOST, false);
        autoDetect = getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTODETECT, false);
        if (id != null) editing = prefs.getServer(id);
        if (editing == null) {
            editing = new ServerProfile();
        } else if (!lockHost) {
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

        if (lockHost) {
            isXtream = true;
            applyType();
            typeRow.setVisibility(View.GONE);
            formTitle.setText(R.string.enter_login);
            formSub.setVisibility(View.VISIBLE);
            formSub.setText(R.string.enter_login_sub);
            inHost.setEnabled(false);
            inHost.setAlpha(0.6f);
            inUser.requestFocus();
        }

        if (autoDetect) {
            // No single provider chosen yet — hide everything that implies one
            // (host field, type toggle, test/delete) and just ask for the two
            // fields that matter. Save now means "find my provider".
            isXtream = true;
            applyType();
            typeRow.setVisibility(View.GONE);
            lblHost.setVisibility(View.GONE);
            inHost.setVisibility(View.GONE);
            btnTest.setVisibility(View.GONE);
            btnDelete.setVisibility(View.GONE);
            formTitle.setText(R.string.auto_signin_title);
            formSub.setVisibility(View.VISIBLE);
            formSub.setText(R.string.auto_signin_sub_form);
            inUser.requestFocus();
        }

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

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (autoDetect) {
                    runAutoDetect();
                    return;
                }
                if (collect()) {
                    prefs.upsertServer(editing);
                    prefs.setActiveServerId(editing.id);
                    repo.clearServer(editing.id);
                    Toast.makeText(AddServerActivity.this, "Saved", Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
        });

        btnTest.setOnClickListener(new View.OnClickListener() {
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

        btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.deleteServer(editing.id);
                repo.clearServer(editing.id);
                Toast.makeText(AddServerActivity.this, "Deleted", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    /**
     * Tries the entered username/password against every preloaded provider,
     * one at a time on a background thread, and stops at the first one that
     * accepts them. Deliberately sequential (not parallel) — these are login
     * attempts against real customer panels, and hammering all 8 at once looks
     * like credential stuffing to a panel's own abuse detection.
     */
    private void runAutoDetect() {
        if (detecting) return;
        final String user = inUser.getText().toString().trim();
        final String pass = inPass.getText().toString().trim();
        if (user.isEmpty() || pass.isEmpty()) {
            formStatus.setTextColor(getResources().getColor(R.color.bad));
            formStatus.setText("Enter your username and password.");
            return;
        }
        detecting = true;
        btnSave.setEnabled(false);
        formStatus.setTextColor(getResources().getColor(R.color.text_secondary));
        formStatus.setText(getString(R.string.auto_signin_checking));

        final List<ServerProfile> candidates = new ArrayList<>(prefs.getServers());
        new Thread(new Runnable() {
            @Override
            public void run() {
                ServerProfile matchTemplate = null;
                for (int i = 0; i < candidates.size(); i++) {
                    final ServerProfile preset = candidates.get(i);
                    if (!preset.isXtream() || preset.host == null || preset.host.isEmpty()) continue;

                    final int attempt = i + 1;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            formStatus.setText(getString(R.string.auto_signin_checking)
                                    + " (" + attempt + "/" + candidates.size() + ")");
                        }
                    });

                    ServerProfile candidate = new ServerProfile();
                    candidate.type = ServerProfile.TYPE_XTREAM;
                    candidate.host = preset.host;
                    candidate.username = user;
                    candidate.password = pass;
                    candidate.userAgent = preset.userAgent;
                    try {
                        XtreamClient.AccountInfo info = new XtreamClient(candidate).authenticate();
                        if (info.authorized) {
                            matchTemplate = preset;
                            break;
                        }
                    } catch (Exception ignored) {
                        // Unreachable/timed out — just move on to the next provider.
                    }
                }

                final ServerProfile matched = matchTemplate;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        detecting = false;
                        btnSave.setEnabled(true);
                        if (matched == null) {
                            formStatus.setTextColor(getResources().getColor(R.color.bad));
                            formStatus.setText(R.string.auto_signin_fail);
                            return;
                        }
                        matched.username = user;
                        matched.password = pass;
                        prefs.upsertServer(matched);
                        prefs.setActiveServerId(matched.id);
                        repo.clearServer(matched.id);
                        Toast.makeText(AddServerActivity.this,
                                "Signed in — " + matched.label(), Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
            }
        }).start();
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

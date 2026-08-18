package com.wm.streamhub.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wm.streamhub.R;
import com.wm.streamhub.chat.ChatClient;
import com.wm.streamhub.model.ChatMessage;
import com.wm.streamhub.net.NetworkMonitor;
import com.wm.streamhub.ui.adapter.ChatAdapter;
import com.wm.streamhub.util.Prefs;

import java.util.List;

/** Talk to the person who sold the line, from the couch. */
@UnstableApi
public class ChatActivity extends AppCompatActivity implements ChatClient.Listener {

    private Prefs prefs;
    private ChatClient chat;
    private ChatAdapter adapter;
    private RecyclerView list;
    private EditText input;
    private TextView state, empty, deviceInfo;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        prefs = new Prefs(this);
        chat = new ChatClient(this);
        chat.setListener(this);

        list = findViewById(R.id.chatList);
        input = findViewById(R.id.chatInput);
        state = findViewById(R.id.chatState);
        empty = findViewById(R.id.chatEmpty);
        deviceInfo = findViewById(R.id.deviceInfo);

        adapter = new ChatAdapter();
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        list.setLayoutManager(lm);
        list.setAdapter(adapter);

        Button send = findViewById(R.id.btnSend);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = input.getText().toString().trim();
                if (text.isEmpty()) return;
                input.setText("");
                chat.send(text);
            }
        });

        wireQuickContacts();
        showDeviceInfo();
        onMessages(chat.cached());
        state.setText(chat.isConfigured() ? "Connecting…" : "Offline mode");
    }

    @Override
    protected void onResume() {
        super.onResume();
        chat.setListener(this);
        chat.registerDevice(false);
        chat.refresh();
        chat.startPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        chat.stopPolling();
    }

    // ------------------------------------------------------------------

    private void showDeviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Account: ").append(prefs.accountRef()).append('\n');
        sb.append("Device ID: ").append(prefs.getDeviceId()).append('\n');
        String name = prefs.getClientName();
        if (!name.isEmpty()) sb.append("Name: ").append(name).append('\n');
        sb.append("App: 1.0.0").append('\n');
        sb.append("Line: ").append(prefs.getServers().size()).append(" server(s)").append('\n');
        NetworkMonitor m = NetworkMonitor.get(this);
        sb.append("Net: ").append(m.connectionLabel()).append("  ").append(m.speedLabel());
        deviceInfo.setText(sb.toString());
    }

    private void wireQuickContacts() {
        Button wa = findViewById(R.id.btnWhatsApp);
        Button tg = findViewById(R.id.btnTelegram);
        Button mail = findViewById(R.id.btnEmail);

        final String waNumber = prefs.getSupportWhatsApp();
        final String tgHandle = prefs.getSupportTelegram();
        final String email = prefs.getSupportEmail();

        wa.setEnabled(!waNumber.isEmpty());
        tg.setEnabled(!tgHandle.isEmpty());
        mail.setEnabled(!email.isEmpty());
        wa.setAlpha(waNumber.isEmpty() ? 0.4f : 1f);
        tg.setAlpha(tgHandle.isEmpty() ? 0.4f : 1f);
        mail.setAlpha(email.isEmpty() ? 0.4f : 1f);

        wa.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open("https://wa.me/" + waNumber.replaceAll("[^0-9]", "")
                        + "?text=" + Uri.encode(defaultMessage()));
            }
        });
        tg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open("https://t.me/" + tgHandle.replace("@", ""));
            }
        });
        mail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + email));
                i.putExtra(Intent.EXTRA_SUBJECT, "Support · device " + prefs.getDeviceId());
                i.putExtra(Intent.EXTRA_TEXT, defaultMessage());
                try {
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(ChatActivity.this,
                            "No email app on this device. Email: " + email,
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private String defaultMessage() {
        return "Hi, I need help with my subscription.\nDevice ID: " + prefs.getDeviceId();
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "No browser on this device.\n" + url, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------

    @Override
    public void onMessages(List<ChatMessage> messages) {
        adapter.submit(messages);
        empty.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
        if (!messages.isEmpty()) list.scrollToPosition(messages.size() - 1);
        if (chat.isConfigured()) state.setText("Connected");
    }

    @Override
    public void onError(String message) {
        state.setText(message.length() > 40 ? "Offline" : message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}

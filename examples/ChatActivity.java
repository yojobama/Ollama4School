package com.example.classroomchat;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.ollama4school.Ollama4School;
import com.ollama4school.OllamaOkHttpClient;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A complete chat screen: finds the school's AI PCs, streams answers token by token,
 * has a Stop button and a New chat button. Layout: activity_chat.xml.
 */
public class ChatActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile Ollama4School ai;

    private TextView status, answer;
    private EditText input;
    private Button send, stop, newChat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        status  = findViewById(R.id.status);
        answer  = findViewById(R.id.answer);
        input   = findViewById(R.id.input);
        send    = findViewById(R.id.send);
        stop    = findViewById(R.id.stop);
        newChat = findViewById(R.id.newChat);

        send.setEnabled(false);
        stop.setVisibility(View.GONE);

        send.setOnClickListener(v -> ask(input.getText().toString().trim()));
        stop.setOnClickListener(v -> { if (ai != null) ai.cancel(); });
        newChat.setOnClickListener(v -> {
            if (ai != null) ai.reset();
            answer.setText("");
        });

        connect();
    }

    private void connect() {
        status.setText("Looking for AI computers...");
        io.execute(() -> {
            try {
                Ollama4School client = Ollama4School.connect(this);   // takes ~3 s
                client.setSystemPrompt("You are a patient tutor for middle-school students. "
                        + "Explain step by step and keep answers short.");
                ai = client;
                runOnUiThread(() -> {
                    status.setText("Connected to " + client.getServers().size() + " computer(s)");
                    send.setEnabled(true);
                });
            } catch (IOException e) {
                runOnUiThread(() -> status.setText("Can't reach the AI server: " + e.getMessage()));
            }
        });
    }

    private void ask(String question) {
        if (question.isEmpty() || ai == null) return;
        input.setText("");
        answer.append("\n\nYou: " + question + "\nAI: ");
        send.setEnabled(false);
        stop.setVisibility(View.VISIBLE);

        io.execute(() -> {
            try {
                ai.askStream(question, new OllamaOkHttpClient.StreamListener() {
                    @Override public void onContent(String token) {
                        runOnUiThread(() -> answer.append(token));
                    }
                    @Override public void onThinking(String token) {
                        runOnUiThread(() -> status.setText("Thinking..."));
                    }
                });
                runOnUiThread(() -> status.setText("Answered by " + ai.getLastServer()));
            } catch (IOException e) {
                // Also thrown after pressing Stop
                runOnUiThread(() -> status.setText("Stopped: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> {
                    send.setEnabled(true);
                    stop.setVisibility(View.GONE);
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ai != null) ai.cancel();
        io.shutdownNow();
    }
}

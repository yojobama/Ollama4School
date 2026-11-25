package com.example.classroomchat;

import android.content.Context;

import com.ollama4school.Ollama4School;
import com.ollama4school.OllamaOkHttpClient;

import java.io.IOException;

/**
 * Short, independent examples of the Ollama4School API.
 * Every method blocks: call them from a background thread, never the main thread.
 */
public class Snippets {

    /** 1. Ask one question and print the whole answer. */
    static void simpleAsk(Context ctx) throws IOException {
        Ollama4School ai = Ollama4School.connect(ctx);
        OllamaOkHttpClient.Reply r = ai.ask("What is photosynthesis?");
        System.out.println(r.content);
        System.out.println("(reasoning: " + r.thinking + ")");
    }

    /** 2. A conversation: the model remembers earlier questions. */
    static void conversation(Context ctx) throws IOException {
        Ollama4School ai = Ollama4School.connect(ctx);
        ai.setSystemPrompt("You are a math teacher. Never give the final answer, only hints.");
        ai.ask("How do I solve 2x + 6 = 14?");
        System.out.println(ai.ask("I got x = 3, is that right?").content);
        ai.reset();   // forget everything, keep the system prompt
    }

    /** 3. Stream the answer and the reasoning separately. */
    static void streaming(Context ctx) throws IOException {
        Ollama4School ai = Ollama4School.connect(ctx);
        ai.askStream("Why is the sky blue?", new OllamaOkHttpClient.StreamListener() {
            @Override public void onContent(String token)  { System.out.print(token); }
            @Override public void onThinking(String token) { /* show a spinner */ }
        });
    }

    /** 4. Skip discovery and use fixed PCs (e.g. Wi-Fi blocks mDNS). */
    static void fixedServers(Context ctx) throws IOException {
        Ollama4School ai = Ollama4School.connect(ctx,
                "http://192.168.1.20:11434", "http://192.168.1.21:11434");
        System.out.println(ai.ask("Hello!").content);
    }

    /** 5. Android emulator talking to Ollama on the development PC. */
    static void emulator(Context ctx) throws IOException {
        Ollama4School ai = Ollama4School.connect(ctx, "http://10.0.2.2:11434");
        System.out.println(ai.ask("Hello!").content);
    }

    /** 6. Inspect and tune the pool. */
    static void poolInfo(Context ctx) throws IOException {
        Ollama4School ai = Ollama4School.connect(ctx);
        ai.pool().setFanout(1);                  // 1 = random PC, default 2 = race two PCs
        ai.ask("Name three planets.");
        System.out.println("Servers:     " + ai.getServers());
        System.out.println("Answered by: " + ai.getLastServer());
        System.out.println("Per PC:      " + ai.pool().stats());
    }

    /** 7. Pick up a PC that was switched on after the app started. */
    static void refresh(Context ctx, Ollama4School ai) throws IOException {
        ai.refreshServers(ctx);
        System.out.println("Now using " + ai.getServers().size() + " PC(s)");
    }

    /** 8. Use a different model (must be installed on the PCs). */
    static void otherModel(Context ctx) throws IOException {
        Ollama4School ai = Ollama4School.connect(ctx);
        ai.setModel("lfm2.5:24b");
        System.out.println(ai.ask("Summarise the French Revolution in two sentences.").content);
    }
}

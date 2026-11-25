package com.ollama4school;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Connects an Android app to every Ollama PC on the network that runs OllamaServer.ps1,
 * spreads the questions across them, and keeps a conversation.
 * Needs OllamaOkHttpClient, ThinkSplitter, OllamaServerFinder and OllamaPool from the same package.
 *
 * Gradle:
 *   implementation("com.squareup.okhttp3:okhttp:4.12.0")
 *   implementation("com.google.code.gson:gson:2.11.0")
 *
 * AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.INTERNET" />
 *   <application android:usesCleartextTraffic="true" ...>   (Ollama is plain http://)
 *
 * How connect() finds the PCs:
 *   mDNS discovery of "_ollama._tcp" on the Wi-Fi (a few seconds), plus any PC that worked last
 *   time and still answers. Every question then goes to the least busy PC (see OllamaPool).
 *
 * Every method blocks: call from a background thread/coroutine, never the main thread.
 *
 * Usage:
 *   Ollama4School ai = Ollama4School.connect(context);
 *   OllamaOkHttpClient.Reply r = ai.ask("What is photosynthesis?");
 *   show(r.content);
 *
 *   // streaming, token by token (callbacks arrive on a background thread):
 *   ai.askStream("And respiration?", token -> runOnUiThread(() -> textView.append(token)));
 */
public final class Ollama4School {

    public static final String DEFAULT_MODEL = "lfm2.5:8b";
    private static final long DISCOVERY_WINDOW_MS = 3000;
    private static final int QUICK_CHECK_TIMEOUT_MS = 2000;
    private static final String PREFS = "ollama4school";

    private final OllamaPool pool;
    private volatile String model;
    private final List<OllamaOkHttpClient.Message> history =
            Collections.synchronizedList(new ArrayList<>());
    private volatile String systemPrompt;

    private Ollama4School(List<String> urls, String model) {
        this.pool = new OllamaPool(urls);
        this.model = model;
    }

    // ---------------------------------------------------------------- connecting

    /** Finds all Ollama PCs on the network (and remembered ones that still answer). */
    public static Ollama4School connect(Context context) throws IOException {
        List<OllamaServerFinder.Server> found = discover(context);
        List<String> urls = new ArrayList<>();
        String model = null;
        for (OllamaServerFinder.Server s : found) {
            urls.add(s.url);
            if (model == null) model = s.model;
        }
        Ollama4School ai = new Ollama4School(urls, model != null ? model : pickModel(urls.get(0)));
        remember(context, urls);
        return ai;
    }

    /** Uses these PCs only, without discovery, e.g. "http://192.168.1.20:11434". */
    public static Ollama4School connect(Context context, String... serverUrls) throws IOException {
        List<String> urls = new ArrayList<>();
        for (String u : serverUrls) {
            String url = stripSlash(u);
            if (isReachable(url)) urls.add(url);
        }
        if (urls.isEmpty()) throw new IOException("None of " + Arrays.toString(serverUrls) + " answers");
        return new Ollama4School(urls, pickModel(urls.get(0)));
    }

    /** Searches the network again, e.g. after a PC was switched on. Keeps the conversation. */
    public void refreshServers(Context context) throws IOException {
        List<String> urls = new ArrayList<>();
        for (OllamaServerFinder.Server s : discover(context)) urls.add(s.url);
        pool.setServers(urls);
        remember(context, urls);
    }

    /** Forgets the remembered PCs. */
    public static void forgetServers(Context context) {
        prefs(context).edit().clear().apply();
    }

    // ---------------------------------------------------------------- conversation

    /** Sends a question, remembers it and the answer in the conversation, returns the reply. */
    public OllamaOkHttpClient.Reply ask(String question) throws IOException {
        return askStream(question, null);
    }

    /** Like ask(), but delivers the answer token by token as it is generated. */
    public OllamaOkHttpClient.Reply askStream(String question,
                                              OllamaOkHttpClient.StreamListener listener)
            throws IOException {
        OllamaOkHttpClient.Message q = OllamaOkHttpClient.Message.user(question);
        try {
            OllamaOkHttpClient.Reply r = pool.chatStream(model, prepare(q), listener);
            history.add(OllamaOkHttpClient.Message.assistant(r.content));
            return r;
        } catch (IOException | RuntimeException e) {
            history.remove(q); // keep the history consistent for the next question
            throw e;
        }
    }

    /** Sets instructions for the model, e.g. "You are a patient tutor for 7th graders." */
    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt;
    }

    /** Starts a new conversation (keeps the system prompt). */
    public void reset() {
        history.clear();
    }

    /** Stops the answer currently being generated; the blocked ask call throws IOException. */
    public void cancel() {
        pool.cancel();
    }

    /**
     * true/false = ask the model to think or not; null (default) = model decides.
     * Note: lfm2.5 reasons even with false, it then just isn't split into Reply.thinking
     * (ThinkSplitter still separates it), so false saves no time with that model.
     */
    public void setThink(Boolean think) {
        pool.setThink(think);
    }

    public void setModel(String model)    { this.model = model; }
    public String getModel()              { return model; }
    /** The PCs questions are spread across. */
    public List<String> getServers()      { return pool.getServers(); }
    /** The PC that answered the last question. */
    public String getLastServer()         { return pool.lastServer(); }
    /** Balancing settings (fanout) and per-PC statistics. */
    public OllamaPool pool()              { return pool; }

    // ---------------------------------------------------------------- helpers

    private List<OllamaOkHttpClient.Message> prepare(OllamaOkHttpClient.Message question) {
        history.add(question);
        List<OllamaOkHttpClient.Message> msgs = new ArrayList<>();
        String sp = systemPrompt;
        if (sp != null && !sp.isEmpty()) msgs.add(OllamaOkHttpClient.Message.system(sp));
        synchronized (history) { msgs.addAll(history); }
        return msgs;
    }

    /** mDNS discovery plus remembered PCs that still answer, without duplicates. */
    private static List<OllamaServerFinder.Server> discover(Context context) throws IOException {
        List<OllamaServerFinder.Server> found = new ArrayList<>();
        Set<String> urls = new LinkedHashSet<>();
        IOException discoveryError = null;
        try {
            for (OllamaServerFinder.Server s : OllamaServerFinder.findAll(context, DISCOVERY_WINDOW_MS)) {
                if (urls.add(s.url)) found.add(s);
            }
        } catch (IOException e) {
            discoveryError = e;
        }
        for (String u : prefs(context).getStringSet("urls", Collections.<String>emptySet())) {
            if (!urls.contains(u) && isReachable(u)) {
                urls.add(u);
                found.add(new OllamaServerFinder.Server(null, u, null));
            }
        }
        if (found.isEmpty()) {
            throw new IOException("No Ollama server found on this network (is the phone on the "
                    + "same Wi-Fi as the PCs?)", discoveryError);
        }
        return found;
    }

    private static void remember(Context context, List<String> urls) {
        prefs(context).edit().putStringSet("urls", new LinkedHashSet<>(urls)).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Fast check (2 s) that an Ollama server answers at url. */
    static boolean isReachable(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url + "/api/version").openConnection();
            c.setConnectTimeout(QUICK_CHECK_TIMEOUT_MS);
            c.setReadTimeout(QUICK_CHECK_TIMEOUT_MS);
            return c.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The server's first installed model, or DEFAULT_MODEL. */
    private static String pickModel(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url + "/api/tags").openConnection();
            c.setConnectTimeout(QUICK_CHECK_TIMEOUT_MS);
            c.setReadTimeout(QUICK_CHECK_TIMEOUT_MS * 2);
            try (java.io.Reader r = new java.io.InputStreamReader(c.getInputStream(), "UTF-8")) {
                JsonArray models = JsonParser.parseReader(r).getAsJsonObject().getAsJsonArray("models");
                if (models != null && models.size() > 0) {
                    return models.get(0).getAsJsonObject().get("name").getAsString();
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return DEFAULT_MODEL;
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}

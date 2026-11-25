package com.ollama4school;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;

/**
 * Load-balances chat requests across several Ollama PCs. Works on Android and plain JVM.
 * Needs OllamaOkHttpClient and ThinkSplitter from the same package.
 *
 * How it balances, with no coordinator and no knowledge of other phones:
 *  Each request is sent to {@code fanout} randomly chosen PCs at once (default 2). Ollama answers
 *  one request at a time per PC, so a busy PC keeps the request queued while an idle one starts
 *  answering right away. The first PC to produce a token wins; the other requests are cancelled,
 *  and Ollama drops a cancelled request from its queue before doing any work on it.
 *  This "power of two choices" keeps queues short on every PC. If a PC is unreachable it is
 *  skipped for 30 s and the request goes to the others.
 *
 * Usage:
 *   OllamaPool pool = new OllamaPool(Arrays.asList("http://192.168.1.20:11434", "http://192.168.1.21:11434"));
 *   OllamaOkHttpClient.Reply r = pool.chat("lfm2.5:8b", messages);
 *   pool.chatStream("lfm2.5:8b", messages, token -> ...);  // callbacks run on a pool thread
 *
 * Every method blocks: call from a background thread on Android.
 */
public final class OllamaPool {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long DOWN_MS = 30_000;

    private final OkHttpClient http;
    private final Gson gson = new Gson();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "OllamaPool");
        t.setDaemon(true);
        return t;
    });

    private final List<String> servers = new CopyOnWriteArrayList<>();
    private final Map<String, Long> downUntil = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> answered = new ConcurrentHashMap<>();
    private final Collection<Call> inFlight = ConcurrentHashMap.newKeySet();
    private volatile int fanout = 2;
    private volatile Boolean think;
    private volatile String lastServer;

    /** How often a waiting request checks that its PC is still alive (PCs can sleep or be switched off). */
    private static final long WATCHDOG_SECONDS = 15;

    private final OkHttpClient probeHttp;
    private final ScheduledExecutorService watchdog = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "OllamaPool-watchdog");
        t.setDaemon(true);
        return t;
    });

    public OllamaPool(Collection<String> serverUrls) {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                // time between bytes: includes waiting in a busy PC's queue; dead PCs are
                // detected by the watchdog instead
                .readTimeout(10, TimeUnit.MINUTES)
                // no connection reuse: a kept-alive connection to a PC that went to sleep looks
                // exactly like a PC that is busy, while a fresh connection fails within seconds
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                .build();
        this.probeHttp = http.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build();
        setServers(serverUrls);
    }

    /** true if the PC answers /api/version on a fresh connection within 3 s. */
    private boolean alive(String url) {
        try (Response r = probeHttp.newCall(new Request.Builder().url(url + "/api/version").build()).execute()) {
            return r.isSuccessful();
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- configuration

    /** Replaces the server list (e.g. after a new discovery). Duplicates are ignored. */
    public void setServers(Collection<String> serverUrls) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String u : serverUrls) clean.add(u.endsWith("/") ? u.substring(0, u.length() - 1) : u);
        servers.clear();
        servers.addAll(clean);
    }

    public List<String> getServers() {
        return new ArrayList<>(servers);
    }

    /** How many PCs each request is sent to; 1 = plain random choice. Default 2. */
    public void setFanout(int fanout) {
        this.fanout = Math.max(1, fanout);
    }

    /** Same meaning as OllamaOkHttpClient.setThink(). */
    public void setThink(Boolean think) {
        this.think = think;
    }

    /** The PC that answered the most recent request. */
    public String lastServer() {
        return lastServer;
    }

    /** How many answers each PC has given through this pool. */
    public Map<String, Integer> stats() {
        Map<String, Integer> m = new java.util.TreeMap<>();
        for (Map.Entry<String, AtomicInteger> e : answered.entrySet()) m.put(e.getKey(), e.getValue().get());
        return m;
    }

    /** Aborts every request in flight; blocked chat calls throw IOException. */
    public void cancel() {
        for (Call c : inFlight) c.cancel();
    }

    // ---------------------------------------------------------------- chat

    public OllamaOkHttpClient.Reply chat(String model, List<OllamaOkHttpClient.Message> messages)
            throws IOException {
        return chatStream(model, messages, null);
    }

    public OllamaOkHttpClient.Reply chatStream(String model, List<OllamaOkHttpClient.Message> messages,
                                               OllamaOkHttpClient.StreamListener listener)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", gson.toJsonTree(messages));
        body.addProperty("stream", true);
        if (think != null) body.addProperty("think", think);

        List<String> order = pick(servers);
        if (order.isEmpty()) throw new IOException("No Ollama servers configured");
        return new Race(order, gson.toJson(body), listener).run();
    }

    /**
     * One request. Keeps {@code fanout} attempts running on different PCs; an attempt that fails
     * before any PC answers is replaced by the next PC at once. The first PC to send a token wins
     * and the remaining attempts are cancelled.
     */
    private final class Race {
        private final ArrayDeque<String> waiting;
        private final String json;
        private final OllamaOkHttpClient.StreamListener listener;
        private final CompletableFuture<OllamaOkHttpClient.Reply> result = new CompletableFuture<>();
        // guarded by this
        private final List<Call> calls = new ArrayList<>();
        private final Set<Call> dead = new HashSet<>(); // cancelled by the watchdog
        private String winner;
        private int running;
        private IOException firstError;

        Race(List<String> order, String json, OllamaOkHttpClient.StreamListener listener) {
            this.waiting = new ArrayDeque<>(order);
            this.json = json;
            this.listener = listener;
        }

        OllamaOkHttpClient.Reply run() throws IOException {
            synchronized (this) {
                for (int i = 0; i < fanout; i++) launchNext();
            }
            try {
                return result.get();
            } catch (InterruptedException e) {
                synchronized (this) { for (Call c : calls) c.cancel(); }
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            } catch (ExecutionException e) {
                Throwable t = e.getCause();
                throw t instanceof IOException ? (IOException) t : new IOException(t);
            }
        }

        /** Starts an attempt on the next PC; fails the request if none are left. Holds the lock. */
        private void launchNext() {
            String url = waiting.poll();
            if (url == null) {
                if (running == 0 && winner == null) {
                    result.completeExceptionally(firstError != null ? firstError
                            : new IOException("No Ollama server answered"));
                }
                return;
            }
            Call call = http.newCall(new Request.Builder()
                    .url(url + "/api/chat")
                    .post(RequestBody.create(json, JSON))
                    .build());
            calls.add(call);
            inFlight.add(call);
            running++;
            workers.execute(() -> attempt(url, call));
        }

        private void attempt(String url, Call call) {
            // While waiting (in the PC's queue or between tokens), make sure the PC is still there.
            ScheduledFuture<?> dog = watchdog.scheduleWithFixedDelay(() -> {
                if (!call.isCanceled() && !alive(url)) {
                    synchronized (this) { dead.add(call); }
                    call.cancel();
                }
            }, WATCHDOG_SECONDS, WATCHDOG_SECONDS, TimeUnit.SECONDS);
            try (Response resp = call.execute()) {
                if (!resp.isSuccessful()) {
                    throw new IOException(url + ": HTTP " + resp.code() + " " + resp.body().string());
                }
                BufferedSource src = resp.body().source();
                String line = nextLine(src); // blocks while this PC is busy with other requests
                if (line == null) throw new IOException(url + ": empty response");
                synchronized (this) {
                    if (winner != null) return; // another PC was faster
                    winner = url;
                    for (Call other : calls) if (other != call) other.cancel();
                }

                downUntil.remove(url);
                OllamaOkHttpClient.Collector c = new OllamaOkHttpClient.Collector(listener);
                while (line != null) {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    if (o.has("error")) throw new IOException(url + ": " + o.get("error").getAsString());
                    JsonObject m = o.getAsJsonObject("message");
                    if (m != null) c.accept(m, "content");
                    if (o.has("done") && o.get("done").getAsBoolean()) break;
                    line = nextLine(src);
                }
                lastServer = url;
                answered.computeIfAbsent(url, k -> new AtomicInteger()).incrementAndGet();
                result.complete(c.finish());
            } catch (IOException | RuntimeException caught) {
                synchronized (this) {
                    boolean pcDied = dead.contains(call);
                    Exception e = pcDied ? new IOException(url + ": PC stopped responding", caught) : caught;
                    if (url.equals(winner)) {
                        // failed mid-answer: tokens were already delivered, so don't retry
                        result.completeExceptionally(e);
                    } else if (winner == null) {
                        running--;
                        if (call.isCanceled() && !pcDied) {
                            // only cancel() cancels before a winner exists
                            result.completeExceptionally(new IOException("Canceled"));
                            for (Call other : calls) other.cancel();
                            return;
                        }
                        downUntil.put(url, System.currentTimeMillis() + DOWN_MS);
                        if (firstError == null) {
                            firstError = e instanceof IOException ? (IOException) e : new IOException(e);
                        }
                        launchNext();
                    }
                }
            } finally {
                dog.cancel(false);
                inFlight.remove(call);
            }
        }
    }

    /** All servers, healthy ones first, each group in random order. */
    private List<String> pick(List<String> from) {
        long now = System.currentTimeMillis();
        List<String> healthy = new ArrayList<>(), down = new ArrayList<>();
        for (String s : from) {
            Long until = downUntil.get(s);
            (until != null && until > now ? down : healthy).add(s);
        }
        Collections.shuffle(healthy);
        Collections.shuffle(down);
        healthy.addAll(down);
        return healthy;
    }

    private static String nextLine(BufferedSource src) throws IOException {
        while (!src.exhausted()) {
            String line = src.readUtf8Line();
            if (line != null && !line.isEmpty()) return line;
        }
        return null;
    }
}

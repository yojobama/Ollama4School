package com.ollama4school;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Looper;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Finds Ollama servers set up with OllamaServer.ps1 on the local network.
 *
 * The PC advertises itself over mDNS / DNS-SD as "<name>._ollama._tcp.local" (default name
 * OLLAMA_SERVER) with TXT entries "model" and "url". This class finds those services with
 * Android's NsdManager. It needs only the INTERNET permission.
 *
 * Every method blocks: call from a background thread, never the main thread.
 *
 * Usage:
 *   OllamaServerFinder.Server s = OllamaServerFinder.find(context, null, 8000);
 *   // s.url = "http://192.168.1.20:11434", s.model = "lfm2.5:8b"
 */
public final class OllamaServerFinder {

    public static final String SERVICE_TYPE = "_ollama._tcp";

    /** One server found on the network. */
    public static final class Server {
        /** DNS-SD instance name, e.g. "OLLAMA_SERVER". */
        public final String name;
        /** Base URL for OllamaOkHttpClient, e.g. "http://192.168.1.20:11434". */
        public final String url;
        /** Model the server preloads (from the TXT record); may be null. */
        public final String model;

        public Server(String name, String url, String model) {
            this.name = name;
            this.url = url;
            this.model = model;
        }

        @Override public String toString() {
            return name + " @ " + url + (model != null ? " (" + model + ")" : "");
        }
    }

    private OllamaServerFinder() { }

    /**
     * Returns the first server found, or the one called {@code name} if that is not null
     * (case-insensitive). Throws IOException if none is found within {@code timeoutMs}.
     */
    public static Server find(Context context, String name, long timeoutMs) throws IOException {
        List<Server> found = search(context, name, timeoutMs, true);
        if (found.isEmpty()) {
            throw new IOException("No Ollama server" + (name != null ? " named " + name : "")
                    + " found on this network (is the phone on the same Wi-Fi as the PC?)");
        }
        return found.get(0);
    }

    /** Lists every server found within {@code windowMs} (for letting the user pick one). */
    public static List<Server> findAll(Context context, long windowMs) throws IOException {
        return search(context, null, windowMs, false);
    }

    // ---------------------------------------------------------------- implementation

    private static List<Server> search(Context context, String wantedName, long timeoutMs,
                                       boolean stopAtFirst) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("OllamaServerFinder blocks; call it off the main thread");
        }
        NsdManager nsd = (NsdManager) context.getApplicationContext()
                .getSystemService(Context.NSD_SERVICE);
        Search s = new Search(nsd, wantedName, stopAtFirst);
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, s);
            synchronized (s) {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                long left;
                while (!s.done && (left = deadline - System.nanoTime()) > 0) {
                    s.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                }
                if (s.error != null && s.results.isEmpty()) throw new IOException(s.error);
                return new ArrayList<>(s.results);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while searching for Ollama servers", e);
        } finally {
            s.stop();
        }
    }

    /**
     * Discovery callbacks plus a resolve queue. Before Android 14 NsdManager can resolve only
     * one service at a time, so found services are resolved one after another.
     */
    private static final class Search implements NsdManager.DiscoveryListener {
        final NsdManager nsd;
        final String wantedName;
        final boolean stopAtFirst;
        final List<Server> results = new ArrayList<>();
        final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
        final List<String> seen = new ArrayList<>();
        boolean resolving, done, discovering, stopped;
        String error;
        Object infoCallback; // NsdManager.ServiceInfoCallback on Android 14+

        Search(NsdManager nsd, String wantedName, boolean stopAtFirst) {
            this.nsd = nsd;
            this.wantedName = wantedName;
            this.stopAtFirst = stopAtFirst;
        }

        @Override public synchronized void onDiscoveryStarted(String type) { discovering = true; }
        @Override public synchronized void onDiscoveryStopped(String type) { discovering = false; }
        @Override public void onServiceLost(NsdServiceInfo info) { }

        @Override public synchronized void onStartDiscoveryFailed(String type, int code) {
            error = "mDNS discovery failed to start (code " + code + ")";
            finish();
        }

        @Override public void onStopDiscoveryFailed(String type, int code) { }

        @Override public synchronized void onServiceFound(NsdServiceInfo info) {
            if (done || seen.contains(info.getServiceName())) return;
            if (wantedName != null && !wantedName.equalsIgnoreCase(info.getServiceName())) return;
            seen.add(info.getServiceName());
            queue.add(info);
            resolveNext();
        }

        private void resolveNext() {
            if (resolving || done || queue.isEmpty()) return;
            resolving = true;
            NsdServiceInfo info = queue.poll();
            if (Build.VERSION.SDK_INT >= 34) {
                resolveApi34(info);
            } else {
                resolveLegacy(info);
            }
        }

        @SuppressWarnings("deprecation")
        private void resolveLegacy(NsdServiceInfo info) {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onServiceResolved(NsdServiceInfo r) {
                    resolved(r, r.getHost());
                }
                @Override public void onResolveFailed(NsdServiceInfo r, int code) {
                    resolved(r, null);
                }
            });
        }

        private void resolveApi34(NsdServiceInfo info) {
            NsdManager.ServiceInfoCallback cb = new NsdManager.ServiceInfoCallback() {
                @Override public void onServiceUpdated(NsdServiceInfo r) {
                    resolved(r, pickAddress(r.getHostAddresses()));
                }
                @Override public void onServiceInfoCallbackRegistrationFailed(int code) {
                    resolved(info, null);
                }
                @Override public void onServiceLost() { }
                @Override public void onServiceInfoCallbackUnregistered() { }
            };
            infoCallback = cb;
            nsd.registerServiceInfoCallback(info, Runnable::run, cb);
        }

        private synchronized void resolved(NsdServiceInfo info, InetAddress host) {
            if (!resolving) return; // late update after we moved on
            resolving = false;
            if (Build.VERSION.SDK_INT >= 34 && infoCallback != null) {
                try { nsd.unregisterServiceInfoCallback((NsdManager.ServiceInfoCallback) infoCallback); }
                catch (IllegalArgumentException ignored) { }
                infoCallback = null;
            }
            Server s = toServer(info, host);
            if (s != null) {
                results.add(s);
                if (stopAtFirst) { finish(); return; }
            }
            resolveNext();
        }

        private void finish() {
            done = true;
            notifyAll();
        }

        synchronized void stop() {
            done = true;
            if (stopped) return;
            stopped = true;
            try { nsd.stopServiceDiscovery(this); } catch (IllegalArgumentException ignored) { }
            if (Build.VERSION.SDK_INT >= 34 && infoCallback != null) {
                try { nsd.unregisterServiceInfoCallback((NsdManager.ServiceInfoCallback) infoCallback); }
                catch (IllegalArgumentException ignored) { }
            }
        }
    }

    /** Builds a Server from a resolved service; falls back to the TXT "url" if no address. */
    static Server toServer(NsdServiceInfo info, InetAddress host) {
        Map<String, byte[]> txt = info.getAttributes();
        String model = txtValue(txt, "model");
        String url;
        if (host != null && info.getPort() > 0) {
            String h = host.getHostAddress();
            if (host instanceof java.net.Inet6Address) {
                int zone = h.indexOf('%');
                h = "[" + (zone >= 0 ? h.substring(0, zone) : h) + "]";
            }
            url = "http://" + h + ":" + info.getPort();
        } else {
            url = txtValue(txt, "url");
        }
        return url == null ? null : new Server(info.getServiceName(), url, model);
    }

    private static InetAddress pickAddress(List<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) return null;
        for (InetAddress a : addresses) if (a instanceof Inet4Address) return a;
        return addresses.get(0);
    }

    private static String txtValue(Map<String, byte[]> txt, String key) {
        if (txt == null) txt = Collections.emptyMap();
        byte[] v = txt.get(key);
        return (v == null || v.length == 0) ? null : new String(v, StandardCharsets.UTF_8);
    }
}

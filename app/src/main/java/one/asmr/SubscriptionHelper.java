package one.asmr;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

final class SubscriptionHelper {
    private SubscriptionHelper() {}

    static JSONArray download(String subscriptionUrl, int connectTimeout) throws Exception {
        URL url = new URL(subscriptionUrl);
        if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol()))
            throw new java.io.IOException("仅支持 HTTP(S) 订阅地址");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "v2rayA/debug WebRequestHelper");
        c.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("订阅请求失败：HTTP " + code);
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new java.util.zip.GZIPInputStream(in);
            StringBuilder raw = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[8192]; int n, total = 0;
                while ((n = reader.read(buf)) != -1) {
                    total += n;
                    if (total > 5 * 1024 * 1024) throw new java.io.IOException("订阅内容超过 5 MB 限制");
                    raw.append(buf, 0, n);
                }
            }
            return parse(raw.toString());
        } finally { c.disconnect(); }
    }

    static JSONArray parse(String raw) throws Exception {
        String text = raw == null ? "" : raw.replace("\r", "").trim();
        if (!text.contains("://")) {
            try {
                String decoded = new String(Base64.decode(normalizeBase64(text), Base64.DEFAULT), StandardCharsets.UTF_8);
                if (decoded.contains("://")) text = decoded;
            } catch (Throwable ignored) {}
        }
        JSONArray out = new JSONArray();
        int id = 1;
        for (String original : text.split("\n")) {
            String line = original.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            try {
                JSONObject node;
                if (line.regionMatches(true, 0, "vmess://", 0, 8)) {
                    JSONObject v = new JSONObject(new String(Base64.decode(normalizeBase64(line.substring(8)), Base64.DEFAULT), StandardCharsets.UTF_8));
                    node = new JSONObject()
                        .put("protocol", "vmess").put("name", v.optString("ps", "Node " + id))
                        .put("address", v.optString("add")).put("port", v.optInt("port"))
                        .put("uuid", v.optString("id")).put("alterId", v.optInt("aid", 0))
                        .put("network", v.optString("net", "tcp"))
                        .put("security", "tls".equalsIgnoreCase(v.optString("tls")) ? "tls" : "none")
                        .put("sni", v.optString("sni")).put("fingerprint", v.optString("fp"))
                        .put("path", v.optString("path")).put("host", v.optString("host"));
                } else if (line.regionMatches(true, 0, "vless://", 0, 8) || line.regionMatches(true, 0, "trojan://", 0, 9)) {
                    boolean vless = line.regionMatches(true, 0, "vless://", 0, 8);
                    URI uri = new URI(line);
                    String protocol = vless ? "vless" : "trojan";
                    String user = uri.getRawUserInfo() == null ? "" : URLDecoder.decode(uri.getRawUserInfo(), "UTF-8");
                    Map<String, String> q = new HashMap<>();
                    if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
                        String[] kv = part.split("=", 2);
                        q.put(URLDecoder.decode(kv[0], "UTF-8"), kv.length > 1 ? URLDecoder.decode(kv[1], "UTF-8") : "");
                    }
                    String name = uri.getRawFragment() == null ? "Node " + id : URLDecoder.decode(uri.getRawFragment(), "UTF-8");
                    node = new JSONObject().put("protocol", protocol).put("name", name)
                        .put("address", uri.getHost()).put("port", uri.getPort())
                        .put("security", q.getOrDefault("security", vless ? "none" : "tls"))
                        .put("network", q.getOrDefault("type", "tcp")).put("sni", q.getOrDefault("sni", ""))
                        .put("fingerprint", q.getOrDefault("fp", "")).put("publicKey", q.getOrDefault("pbk", ""))
                        .put("shortId", q.getOrDefault("sid", "")).put("flow", q.getOrDefault("flow", ""))
                        .put("path", q.getOrDefault("path", "")).put("host", q.getOrDefault("host", ""));
                    node.put(vless ? "uuid" : "password", user);
                } else continue;
                if (node.optString("address").isEmpty() || node.optInt("port") < 1 || node.optInt("port") > 65535) continue;
                node.put("id", id++);
                out.put(node);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    static JSONObject outbound(JSONObject node) throws Exception {
        String protocol = node.optString("protocol");
        JSONObject outbound = new JSONObject().put("protocol", protocol);
        if ("vmess".equals(protocol)) {
            outbound.put("settings", new JSONObject().put("vnext", new JSONArray().put(
                new JSONObject().put("address", node.optString("address")).put("port", node.optInt("port"))
                .put("users", new JSONArray().put(new JSONObject().put("id", node.optString("uuid"))
                .put("alterId", node.optInt("alterId")).put("security", "auto"))))));
        } else if ("vless".equals(protocol)) {
            outbound.put("settings", new JSONObject().put("vnext", new JSONArray().put(
                new JSONObject().put("address", node.optString("address")).put("port", node.optInt("port"))
                .put("users", new JSONArray().put(new JSONObject().put("id", node.optString("uuid"))
                .put("encryption", "none").put("flow", node.optString("flow")))))));
        } else if ("trojan".equals(protocol)) {
            outbound.put("settings", new JSONObject().put("servers", new JSONArray().put(
                new JSONObject().put("address", node.optString("address")).put("port", node.optInt("port"))
                .put("password", node.optString("password")))));
        } else throw new java.io.IOException("不支持的协议：" + protocol);

        String network = node.optString("network", "tcp");
        String security = node.optString("security", "none");
        JSONObject stream = new JSONObject().put("network", network).put("security", security);
        if ("tls".equals(security)) {
            JSONObject tls = new JSONObject();
            if (!node.optString("sni").isEmpty()) tls.put("serverName", node.optString("sni"));
            if (!node.optString("fingerprint").isEmpty()) tls.put("fingerprint", node.optString("fingerprint"));
            stream.put("tlsSettings", tls);
        } else if ("reality".equals(security)) {
            stream.put("realitySettings", new JSONObject().put("serverName", node.optString("sni"))
                .put("fingerprint", node.optString("fingerprint", "chrome"))
                .put("publicKey", node.optString("publicKey")).put("shortId", node.optString("shortId")));
        }
        if ("ws".equals(network)) {
            JSONObject ws = new JSONObject().put("path", node.optString("path", "/"));
            if (!node.optString("host").isEmpty()) ws.put("headers", new JSONObject().put("Host", node.optString("host")));
            stream.put("wsSettings", ws);
        }
        outbound.put("streamSettings", stream);
        return outbound;
    }

    private static String normalizeBase64(String value) {
        String s = value.replaceAll("\\s", "").replace('-', '+').replace('_', '/');
        return s + "=".repeat((4 - s.length() % 4) % 4);
    }
}

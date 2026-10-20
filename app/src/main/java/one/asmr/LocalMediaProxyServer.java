package one.asmr;

import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalMediaProxyServer {
    private static final String TAG = "LocalMediaProxy";
    private final MainActivity activity;
    private ServerSocket serverSocket;
    private int port = -1;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean running = false;

    public LocalMediaProxyServer(MainActivity activity) {
        this.activity = activity;
    }

    public synchronized void start() throws IOException {
        if (running && serverSocket != null && !serverSocket.isClosed()) return;
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        serverSocket.setReuseAddress(true);
        port = serverSocket.getLocalPort();
        running = true;
        Log.i(TAG, "LocalMediaProxyServer started on port " + port);

        Thread acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(30000);
                    clientSocket.setTcpNoDelay(true);
                    executor.execute(() -> handleClient(clientSocket));
                } catch (SocketException se) {
                    if (!running) break;
                    Log.w(TAG, "Server socket exception: " + se.getMessage());
                } catch (Throwable t) {
                    if (!running) break;
                    Log.e(TAG, "Accept error", t);
                }
            }
        }, "media-proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized String getProxyUrl(String targetUrl) {
        try {
            if (!running || serverSocket == null || serverSocket.isClosed()) {
                start();
            }
            return "http://127.0.0.1:" + port + "/stream?url=" + URLEncoder.encode(targetUrl, "UTF-8");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to start proxy or format URL", e);
            return targetUrl;
        }
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        executor.shutdownNow();
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
        }
        if (b == -1 && line.size() == 0) return null;
        return line.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static String canonicalHeader(String h) {
        String lower = h.toLowerCase(Locale.US);
        if ("content-type".equals(lower)) return "Content-Type";
        if ("content-length".equals(lower)) return "Content-Length";
        if ("content-range".equals(lower)) return "Content-Range";
        if ("accept-ranges".equals(lower)) return "Accept-Ranges";
        if ("etag".equals(lower)) return "ETag";
        if ("last-modified".equals(lower)) return "Last-Modified";
        return h;
    }

    private void handleClient(Socket clientSocket) {
        Socket upstreamSocket = null;
        try {
            InputStream clientIn = new BufferedInputStream(clientSocket.getInputStream());
            OutputStream clientOut = new BufferedOutputStream(clientSocket.getOutputStream());

            String reqLine = readAsciiLine(clientIn);
            if (reqLine == null || reqLine.isEmpty()) {
                clientSocket.close();
                return;
            }

            Log.d(TAG, "Client request: " + reqLine);
            String[] reqParts = reqLine.split(" ");
            if (reqParts.length < 2) {
                clientSocket.close();
                return;
            }

            String method = reqParts[0].toUpperCase(Locale.US);
            String path = reqParts[1];

            // Read client request headers
            Map<String, String> clientHeaders = new HashMap<>();
            String headerLine;
            while ((headerLine = readAsciiLine(clientIn)) != null && !headerLine.isEmpty()) {
                int colon = headerLine.indexOf(':');
                if (colon > 0) {
                    clientHeaders.put(headerLine.substring(0, colon).trim().toLowerCase(Locale.US),
                            headerLine.substring(colon + 1).trim());
                }
            }

            // Extract target URL from path: /stream?url=...
            int queryIdx = path.indexOf("url=");
            if (queryIdx < 0) {
                clientOut.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                clientOut.flush();
                return;
            }
            String encodedTarget = path.substring(queryIdx + 4);
            String targetUrl = URLDecoder.decode(encodedTarget, "UTF-8");

            // Follow redirects up to 5 times
            URL currentUrl = new URL(targetUrl);
            int redirects = 0;
            InputStream upstreamIn = null;
            int responseCode = -1;
            String statusLine = "";
            Map<String, String> responseHeaders = new HashMap<>();

            while (redirects < 5) {
                if (upstreamSocket != null) {
                    try { upstreamSocket.close(); } catch (Exception ignored) {}
                }

                boolean useXray = activity.isAsmrUseXray();
                upstreamSocket = activity.openSocketForUrl(currentUrl, useXray);
                upstreamSocket.setSoTimeout(30000);
                upstreamSocket.setTcpNoDelay(true);

                OutputStream upstreamOut = new BufferedOutputStream(upstreamSocket.getOutputStream());
                upstreamIn = new BufferedInputStream(upstreamSocket.getInputStream());

                String file = currentUrl.getFile();
                if (file == null || file.isEmpty()) file = "/";

                StringBuilder req = new StringBuilder();
                req.append(method).append(" ").append(file).append(" HTTP/1.1\r\n");
                req.append("Host: ").append(currentUrl.getHost());
                if (currentUrl.getPort() > 0 && currentUrl.getPort() != 80 && currentUrl.getPort() != 443) {
                    req.append(":").append(currentUrl.getPort());
                }
                req.append("\r\n");

                req.append("User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36\r\n");
                req.append("Accept: */*\r\n");
                req.append("Connection: close\r\n");
                req.append("Referer: https://asmr.one/\r\n");
                req.append("Origin: https://asmr.one\r\n");

                String auth = activity.getAsmrAuthorization();
                if (auth != null && !auth.isEmpty()) {
                    req.append("Authorization: ").append(auth).append("\r\n");
                }

                if (clientHeaders.containsKey("range")) {
                    req.append("Range: ").append(clientHeaders.get("range")).append("\r\n");
                }

                req.append("\r\n");
                upstreamOut.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
                upstreamOut.flush();

                statusLine = readAsciiLine(upstreamIn);
                if (statusLine == null) throw new IOException("Empty upstream response");

                String[] statusParts = statusLine.split(" ", 3);
                if (statusParts.length >= 2) {
                    try { responseCode = Integer.parseInt(statusParts[1]); } catch (Exception ignored) {}
                }

                responseHeaders.clear();
                String respH;
                while ((respH = readAsciiLine(upstreamIn)) != null && !respH.isEmpty()) {
                    int colon = respH.indexOf(':');
                    if (colon > 0) {
                        responseHeaders.put(respH.substring(0, colon).trim().toLowerCase(Locale.US),
                                respH.substring(colon + 1).trim());
                    }
                }

                if (responseCode == 301 || responseCode == 302 || responseCode == 303 || responseCode == 307 || responseCode == 308) {
                    String location = responseHeaders.get("location");
                    if (location != null && !location.isEmpty()) {
                        currentUrl = new URL(currentUrl, location);
                        redirects++;
                        Log.d(TAG, "Redirect (" + responseCode + ") to " + currentUrl);
                        continue;
                    }
                }
                break;
            }

            // Write status line and forwarded headers to client
            clientOut.write((statusLine + "\r\n").getBytes(StandardCharsets.ISO_8859_1));

            String[] forwardHeaders = new String[]{
                    "content-type", "content-length", "content-range", "accept-ranges", "etag", "last-modified"
            };
            for (String h : forwardHeaders) {
                if (responseHeaders.containsKey(h)) {
                    String capH = canonicalHeader(h);
                    clientOut.write((capH + ": " + responseHeaders.get(h) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                }
            }
            clientOut.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();

            if (!"HEAD".equalsIgnoreCase(method) && upstreamIn != null) {
                byte[] buffer = new byte[32768];
                int read;
                while ((read = upstreamIn.read(buffer)) != -1) {
                    clientOut.write(buffer, 0, read);
                    clientOut.flush();
                }
            }
        } catch (SocketException | SocketTimeoutException ignored) {
            // Client closed socket (normal during seek/stop)
        } catch (Throwable e) {
            Log.w(TAG, "Proxy streaming error: " + e.getMessage());
        } finally {
            try { if (upstreamSocket != null) upstreamSocket.close(); } catch (Exception ignored) {}
            try { clientSocket.close(); } catch (Exception ignored) {}
        }
    }
}

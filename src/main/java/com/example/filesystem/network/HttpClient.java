package com.example.filesystem.network;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Cliente HTTP interno para o shell (simula curl).
 */
public class HttpClient {

    public static class HttpResponse {
        private final int statusCode;
        private final String statusMessage;
        private final Map<String, List<String>> headers;
        private final String body;
        private final long responseTime;
        private final String url;

        public HttpResponse(int statusCode, String statusMessage, Map<String, List<String>> headers,
                           String body, long responseTime, String url) {
            this.statusCode = statusCode;
            this.statusMessage = statusMessage;
            this.headers = headers;
            this.body = body;
            this.responseTime = responseTime;
            this.url = url;
        }

        public int getStatusCode() { return statusCode; }
        public String getStatusMessage() { return statusMessage; }
        public Map<String, List<String>> getHeaders() { return headers; }
        public String getBody() { return body; }
        public long getResponseTime() { return responseTime; }
        public String getUrl() { return url; }

        public String getHeader(String name) {
            List<String> values = headers.get(name);
            return values != null && !values.isEmpty() ? values.get(0) : null;
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        public String format(boolean verbose) {
            StringBuilder sb = new StringBuilder();

            if (verbose) {
                sb.append("URL: ").append(url).append("\n");
                sb.append("Status: ").append(statusCode).append(" ").append(statusMessage).append("\n");
                sb.append("Time: ").append(responseTime).append("ms\n");
                sb.append("\nHeaders:\n");
                for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                    if (header.getKey() != null) {
                        sb.append("  ").append(header.getKey()).append(": ")
                          .append(String.join(", ", header.getValue())).append("\n");
                    }
                }
                sb.append("\nBody:\n");
            }

            sb.append(body);
            return sb.toString();
        }

        @Override
        public String toString() {
            return format(false);
        }
    }

    public static class RequestBuilder {
        private String method = "GET";
        private String url;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String body;
        private int timeout = 30000;
        private boolean followRedirects = true;
        private DNSServer dnsServer;

        public RequestBuilder(String url) {
            this.url = url;
        }

        public RequestBuilder method(String method) {
            this.method = method.toUpperCase();
            return this;
        }

        public RequestBuilder get() { return method("GET"); }
        public RequestBuilder post() { return method("POST"); }
        public RequestBuilder put() { return method("PUT"); }
        public RequestBuilder delete() { return method("DELETE"); }
        public RequestBuilder patch() { return method("PATCH"); }

        public RequestBuilder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public RequestBuilder contentType(String type) {
            return header("Content-Type", type);
        }

        public RequestBuilder accept(String type) {
            return header("Accept", type);
        }

        public RequestBuilder authorization(String auth) {
            return header("Authorization", auth);
        }

        public RequestBuilder bearerToken(String token) {
            return header("Authorization", "Bearer " + token);
        }

        public RequestBuilder apiKey(String key) {
            return header("X-API-Key", key);
        }

        public RequestBuilder body(String body) {
            this.body = body;
            return this;
        }

        public RequestBuilder json(String json) {
            this.body = json;
            return contentType("application/json");
        }

        public RequestBuilder timeout(int ms) {
            this.timeout = ms;
            return this;
        }

        public RequestBuilder followRedirects(boolean follow) {
            this.followRedirects = follow;
            return this;
        }

        public RequestBuilder withDNS(DNSServer dns) {
            this.dnsServer = dns;
            return this;
        }

        public HttpResponse execute() throws IOException {
            return HttpClient.execute(this);
        }
    }

    private static final int MAX_REDIRECTS = 5;

    public static RequestBuilder request(String url) {
        return new RequestBuilder(url);
    }

    public static HttpResponse get(String url) throws IOException {
        return new RequestBuilder(url).get().execute();
    }

    public static HttpResponse post(String url, String body) throws IOException {
        return new RequestBuilder(url).post().body(body).execute();
    }

    public static HttpResponse put(String url, String body) throws IOException {
        return new RequestBuilder(url).put().body(body).execute();
    }

    public static HttpResponse delete(String url) throws IOException {
        return new RequestBuilder(url).delete().execute();
    }

    private static HttpResponse execute(RequestBuilder request) throws IOException {
        return execute(request, 0);
    }

    private static HttpResponse execute(RequestBuilder request, int redirectCount) throws IOException {
        Instant start = Instant.now();

        String urlStr = request.url;

        // Resolver DNS interno se necessário
        if (request.dnsServer != null) {
            urlStr = resolveInternalDNS(urlStr, request.dnsServer);
        }

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod(request.method);
            conn.setConnectTimeout(request.timeout);
            conn.setReadTimeout(request.timeout);
            conn.setInstanceFollowRedirects(false); // Gerenciamos manualmente

            // Headers padrão
            conn.setRequestProperty("User-Agent", "CloudFS-HttpClient/1.0");
            conn.setRequestProperty("Accept", "*/*");

            // Headers customizados
            for (Map.Entry<String, String> header : request.headers.entrySet()) {
                conn.setRequestProperty(header.getKey(), header.getValue());
            }

            // Body
            if (request.body != null && !request.body.isEmpty()) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(request.body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int statusCode = conn.getResponseCode();
            String statusMessage = conn.getResponseMessage();

            // Handle redirects
            if (request.followRedirects && isRedirect(statusCode) && redirectCount < MAX_REDIRECTS) {
                String location = conn.getHeaderField("Location");
                if (location != null) {
                    request.url = resolveRedirectUrl(urlStr, location);
                    return execute(request, redirectCount + 1);
                }
            }

            // Read response
            InputStream is;
            try {
                is = conn.getInputStream();
            } catch (IOException e) {
                is = conn.getErrorStream();
            }

            String body = "";
            if (is != null) {
                body = readStream(is);
                is.close();
            }

            long responseTime = Duration.between(start, Instant.now()).toMillis();

            return new HttpResponse(statusCode, statusMessage, conn.getHeaderFields(),
                                   body, responseTime, request.url);

        } finally {
            conn.disconnect();
        }
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 ||
               statusCode == 307 || statusCode == 308;
    }

    private static String resolveRedirectUrl(String baseUrl, String location) {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }
        try {
            URL base = new URL(baseUrl);
            return new URL(base, location).toString();
        } catch (MalformedURLException e) {
            return location;
        }
    }

    private static String resolveInternalDNS(String urlStr, DNSServer dns) {
        try {
            URL url = new URL(urlStr);
            String host = url.getHost();

            // Verificar se é um nome interno
            if (host.endsWith(".internal") || !host.contains(".")) {
                String resolved = dns.resolve(host);
                if (resolved != null) {
                    // Substituir host pelo IP resolvido
                    String newUrl = url.getProtocol() + "://" + resolved;
                    if (url.getPort() != -1) {
                        newUrl += ":" + url.getPort();
                    }
                    newUrl += url.getPath();
                    if (url.getQuery() != null) {
                        newUrl += "?" + url.getQuery();
                    }
                    return newUrl;
                }
            }
        } catch (Exception e) {
            // Retornar URL original se falhar
        }
        return urlStr;
    }

    private static String readStream(InputStream is) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    // Utilitários para parsing de URL
    public static class UrlParser {
        public final String protocol;
        public final String host;
        public final int port;
        public final String path;
        public final String query;

        public UrlParser(String url) throws MalformedURLException {
            URL parsed = new URL(url);
            this.protocol = parsed.getProtocol();
            this.host = parsed.getHost();
            this.port = parsed.getPort() != -1 ? parsed.getPort() :
                       (protocol.equals("https") ? 443 : 80);
            this.path = parsed.getPath().isEmpty() ? "/" : parsed.getPath();
            this.query = parsed.getQuery();
        }
    }
}

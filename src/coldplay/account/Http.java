package coldplay.account;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/** Blocking HTTP helper for the account auth calls. */
final class Http {
    private Http() {
    }

    private static final int TIMEOUT_MILLIS = 10000;

    static final class HttpStatusException extends IOException {
        final int code;
        final String body;

        HttpStatusException(final int code, final String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body;
        }
    }

    /** Throws HttpStatusException carrying the error body on any non-2xx response. */
    static String request(final String url, final String method, final Map<String, String> headers,
                           final String body) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setRequestMethod(method);
        for (final Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }

        if (body != null) {
            connection.setDoOutput(true);
            final byte[] payload = body.getBytes("UTF-8");
            OutputStream out = null;
            try {
                out = connection.getOutputStream();
                out.write(payload);
            } finally {
                if (out != null) {
                    out.close();
                }
            }
        }

        final int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new HttpStatusException(code, readBody(connection.getErrorStream()));
        }
        return readBody(connection.getInputStream());
    }

    static String readBody(final InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        final StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }
}

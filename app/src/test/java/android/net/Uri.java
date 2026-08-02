package android.net;

import java.net.URI;
import java.net.URLDecoder;

public class Uri {
    private final String raw;
    private URI uri;

    private Uri(String raw) {
        this.raw = raw;
        try {
            // Clean/normalize common issues for java.net.URI
            String cleaned = raw.replace(" ", "%20");
            this.uri = new URI(cleaned);
        } catch (Exception e) {
            this.uri = null;
        }
    }

    public static Uri parse(String str) {
        return new Uri(str);
    }

    public String getHost() {
        if (uri != null) {
            try {
                return uri.getHost();
            } catch (Exception e) {}
        }
        // Lenient fallback
        try {
            int schemaIdx = raw.indexOf("://");
            if (schemaIdx == -1) return "";
            String afterSchema = raw.substring(schemaIdx + 3);
            int atIdx = afterSchema.indexOf("@");
            String hostPortPart = (atIdx != -1) ? afterSchema.substring(atIdx + 1) : afterSchema;
            int slashIdx = hostPortPart.indexOf("/");
            int questionIdx = hostPortPart.indexOf("?");
            int hashIdx = hostPortPart.indexOf("#");
            int endIdx = hostPortPart.length();
            if (slashIdx != -1) endIdx = Math.min(endIdx, slashIdx);
            if (questionIdx != -1) endIdx = Math.min(endIdx, questionIdx);
            if (hashIdx != -1) endIdx = Math.min(endIdx, hashIdx);
            String hostPort = hostPortPart.substring(0, endIdx);
            int colonIdx = hostPort.lastIndexOf(":");
            if (colonIdx != -1 && !hostPort.endsWith("]")) {
                return hostPort.substring(0, colonIdx);
            }
            return hostPort;
        } catch (Exception e) {
            return "";
        }
    }

    public int getPort() {
        if (uri != null) {
            try {
                return uri.getPort();
            } catch (Exception e) {}
        }
        // Lenient fallback
        try {
            int schemaIdx = raw.indexOf("://");
            if (schemaIdx == -1) return -1;
            String afterSchema = raw.substring(schemaIdx + 3);
            int atIdx = afterSchema.indexOf("@");
            String hostPortPart = (atIdx != -1) ? afterSchema.substring(atIdx + 1) : afterSchema;
            int slashIdx = hostPortPart.indexOf("/");
            int questionIdx = hostPortPart.indexOf("?");
            int hashIdx = hostPortPart.indexOf("#");
            int endIdx = hostPortPart.length();
            if (slashIdx != -1) endIdx = Math.min(endIdx, slashIdx);
            if (questionIdx != -1) endIdx = Math.min(endIdx, questionIdx);
            if (hashIdx != -1) endIdx = Math.min(endIdx, hashIdx);
            String hostPort = hostPortPart.substring(0, endIdx);
            int colonIdx = hostPort.lastIndexOf(":");
            if (colonIdx != -1 && !hostPort.endsWith("]")) {
                return Integer.parseInt(hostPort.substring(colonIdx + 1));
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public String getUserInfo() {
        if (uri != null) {
            try {
                return uri.getUserInfo();
            } catch (Exception e) {}
        }
        // Lenient fallback
        try {
            int schemaIdx = raw.indexOf("://");
            if (schemaIdx == -1) return "";
            String afterSchema = raw.substring(schemaIdx + 3);
            int atIdx = afterSchema.indexOf("@");
            if (atIdx == -1) return "";
            return afterSchema.substring(0, atIdx);
        } catch (Exception e) {
            return "";
        }
    }

    public String getFragment() {
        // Find fragment leniently (anything after '#')
        int hashIdx = raw.indexOf("#");
        if (hashIdx == -1) return "";
        return raw.substring(hashIdx + 1);
    }

    public String getQueryParameter(String key) {
        int questionIdx = raw.indexOf("?");
        if (questionIdx == -1) return null;
        int hashIdx = raw.indexOf("#");
        String query = (hashIdx == -1) ? raw.substring(questionIdx + 1) : raw.substring(questionIdx + 1, hashIdx);
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length > 0 && decode(pair[0]).equalsIgnoreCase(key)) {
                return pair.length > 1 ? decode(pair[1]) : "";
            }
        }
        return null;
    }

    public static String decode(String s) {
        if (s == null) return "";
        try {
            // URLDecoder expects %2B for '+' to not be decoded as space.
            // But android.net.Uri.decode just decodes hex percent escapes.
            // Let's implement decoding similar to android.net.Uri.decode:
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public static String encode(String s) {
        if (s == null) return "";
        try {
            return java.net.URLEncoder.encode(s, "UTF-8")
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
        } catch (Exception e) {
            return s;
        }
    }
}

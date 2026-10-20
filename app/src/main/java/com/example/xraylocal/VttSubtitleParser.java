package com.example.xraylocal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VttSubtitleParser {

    public static final class Cue {
        public final long startMs;
        public final long endMs;
        public final String text;

        public Cue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text != null ? text.trim() : "";
        }

        @Override
        public String toString() {
            return "[" + startMs + "ms -> " + endMs + "ms]: " + text;
        }
    }

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern LRC_LINE_PATTERN = Pattern.compile("^\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?\\](.*)$");

    public static List<Cue> parse(String content) {
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }
        // Remove UTF-8 BOM if present
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }

        String[] rawLines = content.split("\r?\n");
        if (isLrcFormat(rawLines)) {
            return parseLrc(rawLines);
        }
        return parseVtt(rawLines);
    }

    private static boolean isLrcFormat(String[] lines) {
        int lrcMatches = 0;
        for (int i = 0; i < Math.min(lines.length, 20); i++) {
            String line = lines[i].trim();
            if (line.startsWith("WEBVTT")) return false;
            if (LRC_LINE_PATTERN.matcher(line).matches()) {
                lrcMatches++;
            }
        }
        return lrcMatches >= 2;
    }

    private static List<Cue> parseVtt(String[] lines) {
        List<Cue> cues = new ArrayList<>();
        int i = 0;

        // Skip headers until first cue
        while (i < lines.length) {
            String line = lines[i].trim();
            if (line.contains("-->")) {
                break;
            }
            i++;
        }

        while (i < lines.length) {
            String line = lines[i].trim();

            if (line.contains("-->")) {
                String[] parts = line.split("-->");
                if (parts.length >= 2) {
                    long startMs = parseTimestamp(parts[0].trim());
                    // End time may have trailing settings like 'line:0 position:50%'
                    String endStr = parts[1].trim().split("\\s+")[0];
                    long endMs = parseTimestamp(endStr);

                    // Collect text lines
                    i++;
                    StringBuilder textSb = new StringBuilder();
                    while (i < lines.length) {
                        String textLine = lines[i];
                        if (textLine.trim().isEmpty()) {
                            break;
                        }
                        if (textLine.contains("-->")) {
                            // Hit next cue without blank line
                            i--; // Re-process this line on next loop
                            break;
                        }
                        if (textSb.length() > 0) {
                            textSb.append("\n");
                        }
                        textSb.append(textLine);
                        i++;
                    }

                    String cleanedText = cleanSubtitleText(textSb.toString());
                    if (!cleanedText.isEmpty() && endMs >= startMs) {
                        cues.add(new Cue(startMs, endMs, cleanedText));
                    }
                }
            }
            i++;
        }

        Collections.sort(cues, Comparator.comparingLong(c -> c.startMs));
        return cues;
    }

    private static List<Cue> parseLrc(String[] lines) {
        List<Cue> rawCues = new ArrayList<>();
        for (String rawLine : lines) {
            String line = rawLine.trim();
            Matcher m = LRC_LINE_PATTERN.matcher(line);
            if (m.matches()) {
                try {
                    long min = Long.parseLong(m.group(1));
                    long sec = Long.parseLong(m.group(2));
                    String msStr = m.group(3);
                    long ms = 0;
                    if (msStr != null && !msStr.isEmpty()) {
                        if (msStr.length() == 1) ms = Long.parseLong(msStr) * 100;
                        else if (msStr.length() == 2) ms = Long.parseLong(msStr) * 10;
                        else ms = Long.parseLong(msStr.substring(0, 3));
                    }
                    long totalMs = min * 60000 + sec * 1000 + ms;
                    String text = cleanSubtitleText(m.group(4));
                    if (!text.isEmpty()) {
                        rawCues.add(new Cue(totalMs, totalMs + 4000, text));
                    }
                } catch (Exception ignored) {}
            }
        }
        Collections.sort(rawCues, Comparator.comparingLong(c -> c.startMs));

        // Adjust end times to match next cue start
        List<Cue> cues = new ArrayList<>();
        for (int i = 0; i < rawCues.size(); i++) {
            Cue cur = rawCues.get(i);
            long end = cur.endMs;
            if (i + 1 < rawCues.size()) {
                Cue next = rawCues.get(i + 1);
                end = Math.min(end, next.startMs);
            }
            cues.add(new Cue(cur.startMs, end, cur.text));
        }
        return cues;
    }

    private static long parseTimestamp(String timeStr) {
        try {
            timeStr = timeStr.replace(',', '.').trim();
            String[] parts = timeStr.split(":");
            if (parts.length == 3) {
                long h = Long.parseLong(parts[0]);
                long m = Long.parseLong(parts[1]);
                double s = Double.parseDouble(parts[2]);
                return (long) ((h * 3600 + m * 60 + s) * 1000.0);
            } else if (parts.length == 2) {
                long m = Long.parseLong(parts[0]);
                double s = Double.parseDouble(parts[1]);
                return (long) ((m * 60 + s) * 1000.0);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    public static String cleanSubtitleText(String raw) {
        if (raw == null) return "";
        // Remove VTT tags like <v Voice>, <c.color>, <b>, <i>, <u>, etc.
        String text = TAG_PATTERN.matcher(raw).replaceAll("");
        // Unescape HTML entities
        text = text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
        return text.trim();
    }

    public static String getCueText(List<Cue> cues, long positionMs) {
        if (cues == null || cues.isEmpty()) return "";
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            if (positionMs >= cue.startMs && positionMs <= cue.endMs) {
                return cue.text;
            }
            // Small gap grace period: keep the subtitle visible for 1s after endMs if no next cue yet
            if (positionMs > cue.endMs && positionMs <= cue.endMs + 1000) {
                if (i + 1 == cues.size() || positionMs < cues.get(i + 1).startMs) {
                    return cue.text;
                }
            }
            if (positionMs < cue.startMs) {
                break;
            }
        }
        return "";
    }
}

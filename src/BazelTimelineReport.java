import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Converts a Bazel JSON trace profile (`--profile=...json.gz`) into one self-contained HTML report.
 *
 * Usage: `java BazelTimelineReport.java <profile.json[.gz]> <template.html> <out.html>`
 */
public final class BazelTimelineReport {
  private static final Set<String> SERVICE_THREADS =
    Set.of("Main Thread", "Critical Path", "Garbage Collector", "collect-local-resources");
  private static final Set<String> NON_LANE_CATEGORIES = Set.of("gc notification", "critical path component");
  private static final int MAX_COLOR_KEYS = 7;

  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      System.err.println("Usage: java BazelTimelineReport.java <profile.json[.gz]> <template.html> <out.html>");
      System.exit(2);
    }
    Map<String, Object> profile = asMap(Json.parse(readProfile(Path.of(args[0]))));
    String data = new BazelTimelineReport(profile).toJson();
    String template = Files.readString(Path.of(args[1]));
    String html = template.replace("/*__REPORT_DATA__*/null", data.replace("</", "<\\/"));
    Files.writeString(Path.of(args[2]), html);
    System.out.println("Report: " + Path.of(args[2]).toAbsolutePath());
  }

  private record Span(long start, long dur, String category, String name, String mnemonic, String target, long tid) {
    long end() { return start + dur; }
  }

  private record Sample(long ts, Map<String, Object> values) {}

  private final Map<String, Object> otherData;
  private final Map<Long, String> threadNames = new HashMap<>();
  private final List<Span> spans = new ArrayList<>();
  private final List<Span> criticalPath = new ArrayList<>();
  private final List<Span> gcEvents = new ArrayList<>();
  private final List<Map<String, Object>> markers = new ArrayList<>();
  private final Map<String, List<Sample>> counters = new LinkedHashMap<>();
  private long minTs = 0;
  private long maxTs = 0;

  private BazelTimelineReport(Map<String, Object> profile) {
    otherData = profile.get("otherData") instanceof Map<?, ?> m ? asMap(m) : Map.of();
    for (Object o : (List<?>)profile.get("traceEvents")) {
      collect(asMap(o));
    }
  }

  private void collect(Map<String, Object> e) {
    String ph = str(e.get("ph"));
    long tid = num(e.get("tid"));
    long ts = num(e.get("ts"));
    String name = str(e.get("name"));
    String cat = str(e.get("cat"));
    Map<String, Object> args = e.get("args") instanceof Map<?, ?> m ? asMap(m) : Map.of();
    switch (ph) {
      case "M" -> {
        if (name.equals("thread_name")) threadNames.put(tid, str(args.get("name")));
        return;
      }
      case "C" -> counters.computeIfAbsent(name, k -> new ArrayList<>()).add(new Sample(ts, args));
      case "i", "I" -> {
        if (cat.equals("build phase marker")) markers.add(Map.of("name", name, "ts", ts));
      }
      case "X" -> {
        Span span = new Span(ts, num(e.get("dur")), cat, name, str(args.get("mnemonic")), str(args.get("target")), tid);
        switch (cat) {
          case "critical path component" -> criticalPath.add(span);
          case "gc notification" -> gcEvents.add(span);
          case "build phase marker" -> markers.add(Map.of("name", name, "ts", ts));
          default -> spans.add(span);
        }
        maxTs = Math.max(maxTs, span.end());
      }
      default -> {}
    }
    minTs = Math.min(minTs, ts);
    maxTs = Math.max(maxTs, ts);
  }

  /** Keeps only the outermost spans of each worker thread: nested spans are details of their parent. */
  private Map<String, List<Span>> buildLanes() {
    Map<Long, List<Span>> byThread = new HashMap<>();
    for (Span s : spans) {
      String thread = threadNames.getOrDefault(s.tid(), "thread-" + s.tid());
      if (SERVICE_THREADS.contains(thread) || NON_LANE_CATEGORIES.contains(s.category())) continue;
      byThread.computeIfAbsent(s.tid(), k -> new ArrayList<>()).add(s);
    }
    Map<Long, List<Span>> outer = new HashMap<>();
    byThread.forEach((tid, list) -> {
      list.sort(Comparator.comparingLong(Span::start).thenComparing(Comparator.comparingLong(Span::dur).reversed()));
      List<Span> kept = new ArrayList<>();
      long end = Long.MIN_VALUE;
      for (Span s : list) {
        if (s.start() >= end) {
          kept.add(s);
          end = s.end();
        }
      }
      outer.put(tid, kept);
    });

    // Platform threads keep their own lane. Virtual threads are short-lived, so they are packed into shared lanes.
    Map<String, List<Span>> lanes = new TreeMap<>(BazelTimelineReport::compareLaneNames);
    List<Span> virtual = new ArrayList<>();
    outer.forEach((tid, list) -> {
      String thread = threadNames.getOrDefault(tid, "thread-" + tid);
      if (thread.endsWith("(Virtual)")) virtual.addAll(list);
      else lanes.put(thread, list);
    });
    virtual.sort(Comparator.comparingLong(Span::start));
    List<List<Span>> packed = new ArrayList<>();
    for (Span s : virtual) {
      List<Span> target = null;
      for (List<Span> lane : packed) {
        if (lane.getLast().end() <= s.start()) {
          target = lane;
          break;
        }
      }
      if (target == null) packed.add(target = new ArrayList<>());
      target.add(s);
    }
    for (int i = 0; i < packed.size(); i++) lanes.put("virtual-" + i, packed.get(i));
    return lanes;
  }

  /** Sorts `skyframe-evaluator-execution-2` before `skyframe-evaluator-execution-10`. */
  private static int compareLaneNames(String a, String b) {
    String pa = a.replaceAll("\\d+$", ""), pb = b.replaceAll("\\d+$", "");
    if (!pa.equals(pb)) return laneGroupOrder(pa) != laneGroupOrder(pb) ? laneGroupOrder(pa) - laneGroupOrder(pb) : pa.compareTo(pb);
    return Long.compare(trailingNumber(a), trailingNumber(b));
  }

  private static int laneGroupOrder(String prefix) {
    if (prefix.startsWith("skyframe-evaluator-execution")) return 0;
    if (prefix.startsWith("skyframe-evaluator-memoizing")) return 2;
    if (prefix.startsWith("skyframe-evaluator")) return 1;
    if (prefix.startsWith("virtual")) return 4;
    return 3;
  }

  private static long trailingNumber(String s) {
    String digits = s.replaceAll("^.*?(\\d*)$", "$1");
    return digits.isEmpty() ? -1 : Long.parseLong(digits);
  }

  /** The color key of a span: the mnemonic for an action, the event name for general information, else the category. */
  private static String colorKey(Span s) {
    if (s.category().equals("action processing") && !s.mnemonic().isEmpty()) return s.mnemonic();
    if (s.category().equals("general information")) return s.name();
    return s.category();
  }

  private String toJson() {
    Map<String, List<Span>> lanes = buildLanes();

    Map<String, Long> timeByKey = new HashMap<>();
    lanes.values().forEach(list -> list.forEach(s -> timeByKey.merge(colorKey(s), s.dur(), Long::sum)));
    List<String> keys = new ArrayList<>(timeByKey.keySet());
    keys.sort(Comparator.comparingLong((String k) -> timeByKey.get(k)).reversed());
    List<String> colorKeys = new ArrayList<>(keys.subList(0, Math.min(MAX_COLOR_KEYS, keys.size())));
    boolean hasOther = keys.size() > colorKeys.size();
    if (hasOther) colorKeys.add("Other");

    Set<String> criticalNames = new HashSet<>();
    for (Span s : criticalPath) {
      String n = s.name();
      criticalNames.add(n.startsWith("action '") && n.endsWith("'") ? n.substring(8, n.length() - 1) : n);
    }

    Map<String, Integer> strings = new LinkedHashMap<>();
    StringBuilder out = new StringBuilder(1 << 20);
    out.append("{\"meta\":{");
    field(out, "bazelVersion", str(otherData.get("bazel_version"))).append(',');
    field(out, "buildId", str(otherData.get("build_id"))).append(',');
    field(out, "date", str(otherData.get("date"))).append(',');
    field(out, "outputBase", str(otherData.get("output_base"))).append(',');
    out.append("\"start\":").append(minTs).append(",\"end\":").append(maxTs).append("},");

    out.append("\"keys\":[");
    for (int i = 0; i < colorKeys.size(); i++) {
      if (i > 0) out.append(',');
      quote(out, colorKeys.get(i));
    }
    out.append("],");

    out.append("\"lanes\":[");
    boolean firstLane = true;
    for (var lane : lanes.entrySet()) {
      if (!firstLane) out.append(',');
      firstLane = false;
      out.append("{\"name\":");
      quote(out, lane.getKey());
      // A span is [start, dur, colorKey, name, detail, critical].
      out.append(",\"spans\":[");
      boolean first = true;
      for (Span s : lane.getValue()) {
        if (!first) out.append(',');
        first = false;
        int key = colorKeys.indexOf(colorKey(s));
        if (key < 0) key = colorKeys.size() - 1;
        String detail = s.category() + (s.mnemonic().isEmpty() ? "" : " · " + s.mnemonic()) + (s.target().isEmpty() ? "" : " · " + s.target());
        out.append('[').append(s.start()).append(',').append(s.dur()).append(',').append(key).append(',')
          .append(intern(strings, s.name())).append(',').append(intern(strings, detail)).append(',')
          .append(criticalNames.contains(s.name()) ? 1 : 0).append(']');
      }
      out.append("]}");
    }
    out.append("],");

    out.append("\"criticalPath\":");
    spanList(out, criticalPath);
    out.append(",\"gc\":");
    spanList(out, gcEvents);

    markers.sort(Comparator.comparingLong(m -> (Long)m.get("ts")));
    out.append(",\"phases\":[");
    for (int i = 0; i < markers.size(); i++) {
      if (i > 0) out.append(',');
      long start = (Long)markers.get(i).get("ts");
      long end = i + 1 < markers.size() ? (Long)markers.get(i + 1).get("ts") : maxTs;
      out.append("{\"name\":");
      quote(out, (String)markers.get(i).get("name"));
      out.append(",\"start\":").append(start).append(",\"end\":").append(end).append('}');
    }
    out.append("],");

    out.append("\"counters\":[");
    boolean firstCounter = true;
    for (var counter : counters.entrySet()) {
      Map<String, List<double[]>> series = new LinkedHashMap<>();
      for (Sample sample : counter.getValue()) {
        sample.values().forEach((k, v) -> {
          if (v instanceof Number n) series.computeIfAbsent(k, x -> new ArrayList<>()).add(new double[]{sample.ts(), n.doubleValue()});
        });
      }
      if (series.isEmpty()) continue;
      if (!firstCounter) out.append(',');
      firstCounter = false;
      out.append("{\"name\":");
      quote(out, counter.getKey());
      out.append(",\"series\":[");
      boolean firstSeries = true;
      for (var s : series.entrySet()) {
        if (!firstSeries) out.append(',');
        firstSeries = false;
        out.append("{\"name\":");
        quote(out, s.getKey());
        out.append(",\"points\":[");
        boolean firstPoint = true;
        for (double[] p : s.getValue()) {
          if (!firstPoint) out.append(',');
          firstPoint = false;
          out.append('[').append((long)p[0]).append(',').append(String.format(Locale.ROOT, "%.4g", p[1])).append(']');
        }
        out.append("]}");
      }
      out.append("]}");
    }
    out.append("],");

    out.append("\"strings\":[");
    boolean firstString = true;
    for (String s : strings.keySet()) {
      if (!firstString) out.append(',');
      firstString = false;
      quote(out, s);
    }
    out.append("]}");
    return out.toString();
  }

  private static void spanList(StringBuilder out, List<Span> list) {
    out.append('[');
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) out.append(',');
      Span s = list.get(i);
      out.append("{\"start\":").append(s.start()).append(",\"dur\":").append(s.dur()).append(",\"name\":");
      quote(out, s.name());
      out.append('}');
    }
    out.append(']');
  }

  private static int intern(Map<String, Integer> strings, String s) {
    return strings.computeIfAbsent(s, k -> strings.size());
  }

  private static StringBuilder field(StringBuilder out, String name, String value) {
    quote(out, name);
    out.append(':');
    quote(out, value);
    return out;
  }

  private static void quote(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format("\\u%04x", (int)c));
          else out.append(c);
        }
      }
    }
    out.append('"');
  }

  private static String readProfile(Path path) throws IOException {
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(path))) {
      raw.mark(2);
      boolean gzip = raw.read() == 0x1f && raw.read() == 0x8b;
      raw.reset();
      InputStream in = gzip ? new GZIPInputStream(raw, 1 << 16) : raw;
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return (Map<String, Object>)o;
  }

  private static String str(Object o) {
    return o == null ? "" : o.toString();
  }

  private static long num(Object o) {
    return o instanceof Number n ? n.longValue() : 0;
  }

  /** A minimal JSON parser, so the tool needs only a JDK. */
  private static final class Json {
    private final String s;
    private int pos;

    private Json(String s) {
      this.s = s;
    }

    static Object parse(String s) {
      Json json = new Json(s);
      Object value = json.value();
      json.skipWhitespace();
      if (json.pos != s.length()) throw json.error("trailing data");
      return value;
    }

    private Object value() {
      skipWhitespace();
      if (pos >= s.length()) throw error("unexpected end");
      char c = s.charAt(pos);
      return switch (c) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> string();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> number();
      };
    }

    private Map<String, Object> object() {
      Map<String, Object> map = new LinkedHashMap<>();
      pos++;
      skipWhitespace();
      if (s.charAt(pos) == '}') {
        pos++;
        return map;
      }
      while (true) {
        skipWhitespace();
        String key = string();
        skipWhitespace();
        expect(':');
        map.put(key, value());
        skipWhitespace();
        char c = s.charAt(pos++);
        if (c == '}') return map;
        if (c != ',') throw error("expected ',' or '}'");
      }
    }

    private List<Object> array() {
      List<Object> list = new ArrayList<>();
      pos++;
      skipWhitespace();
      if (s.charAt(pos) == ']') {
        pos++;
        return list;
      }
      while (true) {
        list.add(value());
        skipWhitespace();
        char c = s.charAt(pos++);
        if (c == ']') return list;
        if (c != ',') throw error("expected ',' or ']'");
      }
    }

    private String string() {
      expect('"');
      StringBuilder sb = null;
      int start = pos;
      while (true) {
        char c = s.charAt(pos++);
        if (c == '"') {
          return sb == null ? s.substring(start, pos - 1) : sb.toString();
        }
        if (c == '\\') {
          if (sb == null) sb = new StringBuilder(s.substring(start, pos - 1));
          char esc = s.charAt(pos++);
          switch (esc) {
            case 'n' -> sb.append('\n');
            case 't' -> sb.append('\t');
            case 'r' -> sb.append('\r');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'u' -> {
              sb.append((char)Integer.parseInt(s.substring(pos, pos + 4), 16));
              pos += 4;
            }
            default -> sb.append(esc);
          }
        }
        else if (sb != null) {
          sb.append(c);
        }
      }
    }

    private Object number() {
      int start = pos;
      boolean fraction = false;
      while (pos < s.length()) {
        char c = s.charAt(pos);
        if (c == '.' || c == 'e' || c == 'E') fraction = true;
        else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) break;
        pos++;
      }
      if (start == pos) throw error("unexpected character");
      String text = s.substring(start, pos);
      return fraction ? (Object)Double.parseDouble(text) : (Object)Long.parseLong(text);
    }

    private Object literal(String text, Object value) {
      if (!s.startsWith(text, pos)) throw error("expected " + text);
      pos += text.length();
      return value;
    }

    private void expect(char c) {
      if (s.charAt(pos) != c) throw error("expected '" + c + "'");
      pos++;
    }

    private void skipWhitespace() {
      while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    private IllegalStateException error(String message) {
      return new IllegalStateException(message + " at offset " + pos);
    }
  }
}

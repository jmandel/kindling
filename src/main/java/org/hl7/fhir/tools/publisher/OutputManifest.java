package org.hl7.fhir.tools.publisher;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * Normalized manifest of a publish directory, plus the judge ("compare") and "impact"
 * verdicts built on top of it. This is the Java owner of the manifest format previously
 * the output-manifest format (HASH\\t relpath); see SpecBuild and FUTURE.md.
 *
 * <p>Manifest format: one line per file, sorted by relative path:
 * <pre>
 *   N:&lt;16hex&gt;/O:&lt;16hex&gt;\t&lt;relative/path&gt;   text files (normalized / order-insensitive)
 *   X:&lt;16hex&gt;\t&lt;relative/path&gt;             .xlsx (deep member hash, font-metric variance removed)
 *   A:&lt;16hex&gt;\t&lt;relative/path&gt;             other archives (.zip .jar .pack .tgz: sorted member listing)
 *   B:&lt;16hex&gt;\t&lt;relative/path&gt;             binaries (raw content hash)
 * </pre>
 * Hashes are the first 16 hex chars of sha256. Text files are hashed twice: N over the
 * timestamp/uuid/path-normalized bytes, and O over the sorted multiset of trimmed non-empty
 * lines, so pure element reordering (the documented stock-build nondeterminism class)
 * compares equal under O while any content change still flags.
 *
 * <p>Commands:
 * <pre>
 *   manifest &lt;publishDir&gt;                                        write manifest to stdout
 *   compare  &lt;ref&gt; &lt;new&gt; [-prev &lt;evidence&gt;] [-allowlist &lt;file&gt;]  judge new against ref; exit 1 on unexplained diffs
 *   impact   &lt;prev&gt; &lt;new&gt;                                        content-level changes (+ added, - removed); exit 0
 * </pre>
 *
 * <p>The judge excuses: ordering-only changes (O hashes equal); .shex/.shex.html/.xls files and
 * all-valuesets.zip (always); files that provably varied between two same-commit builds when an
 * evidence manifest is supplied with -prev; and members of the static noise allowlist.
 */
public class OutputManifest {

  // patterns replaced with the literal bytes "TS" before hashing text content, in order:
  private static final List<Pattern> TS_PATTERNS = compileAll(
      // ISO datetimes 2026-06-11T09:45:07.123-05:00 / with space / Z
      "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([+-]\\d{2}:?\\d{2}|Z)?",
      // Thu, Jun 11, 2026 09:45-0500 (kindling page footer style)
      "(Mon|Tue|Wed|Thu|Fri|Sat|Sun), [A-Z][a-z]{2} \\d{1,2}, \\d{4} \\d{2}:\\d{2}([+-]\\d{4})?",
      // the build embeds the OS username in page footers ("Local Build (jmandel)")
      "Local Build \\([^)]*\\)",
      // bare times like 09:45:07
      "\\b\\d{2}:\\d{2}:\\d{2}\\b",
      // render dates like "11 Jun 2026" (expansion-generated lines on valueset pages)
      "\\b\\d{1,2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{4}\\b",
      // random UUIDs in generated html (table script ids, image names)
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
      // section numbers: unstable across identical stock runs (HashMap order in vs/cs numbering)
      "sectioncount\">[\\d.]+",
      "<a name=\"[\\d.]+\">",
      "#[\\d.]+\" title=\"link to here\"");

  // POI auto-sizes spreadsheet column widths using the JVM's installed fonts, so width
  // attributes differ across machines while the actual sheet content is identical
  private static final Pattern XLSX_WIDTH = Pattern.compile("width=\"[0-9.]+\"");

  private static final String[] ARCHIVE_EXTS = { ".zip", ".jar", ".pack", ".tgz" };
  private static final String[] BINARY_EXTS = { ".png", ".gif", ".jpg", ".jpeg", ".ico", ".eot",
      ".woff", ".woff2", ".ttf", ".pdf", ".epub", ".exe", ".dll", ".class" };

  // judge exclusions that always apply (known-variable generated artifacts)
  private static final Pattern EXCLUDED_PATHS = Pattern.compile(".*(\\.shex(\\.html)?|\\.xls)$|^all-valuesets\\.zip$");

  public static void main(String[] args) throws Exception {
    System.exit(run(args, System.out));
  }

  public static int run(String[] args, PrintStream out) throws IOException {
    if (args.length == 0) {
      usage(out);
      return 2;
    }
    switch (args[0]) {
    case "manifest":
      if (args.length != 2) {
        usage(out);
        return 2;
      }
      for (String line : generateManifest(new File(args[1])))
        out.println(line);
      return 0;
    case "compare": {
      if (args.length < 3) {
        usage(out);
        return 2;
      }
      Map<String, String> ref = readManifest(new File(args[1]));
      Map<String, String> nw = readManifest(new File(args[2]));
      Map<String, String> prev = null;
      Set<String> allowlist = Collections.emptySet();
      for (int i = 3; i < args.length; i++) {
        if ("-prev".equals(args[i]) && i + 1 < args.length)
          prev = readManifest(new File(args[++i]));
        else if ("-allowlist".equals(args[i]) && i + 1 < args.length)
          allowlist = readAllowlist(new File(args[++i]));
        else {
          usage(out);
          return 2;
        }
      }
      CompareResult res = compare(ref, nw, prev, allowlist);
      if (prev != null)
        out.println("(evidence-based excusal: " + res.excused + " files varied between this run's two builds)");
      if (!res.unexplained.isEmpty()) {
        out.println("UNEXPLAINED OUTPUT DIFFS (beyond known build nondeterminism):");
        for (String p : res.unexplained)
          out.println(p);
        return 1;
      }
      out.println("byte parity: clean (content-level diffs: " + res.contentDiffs + ", all evidenced or known-nondeterministic)");
      return 0;
    }
    case "impact": {
      if (args.length != 3) {
        usage(out);
        return 2;
      }
      for (String line : impact(readManifest(new File(args[1])), readManifest(new File(args[2]))))
        out.println(line);
      return 0;
    }
    default:
      usage(out);
      return 2;
    }
  }

  private static void usage(PrintStream out) {
    out.println("Usage: OutputManifest <command> ...");
    out.println("  manifest <publishDir>");
    out.println("      print a normalized manifest of the publish dir to stdout, one");
    out.println("      'HASH\\trelative/path' line per file, sorted by path. Hashes ignore");
    out.println("      timestamps, uuids, the checkout path and other known build noise");
    out.println("  compare <refManifest> <newManifest> [-prev <evidenceManifest>] [-allowlist <file>]");
    out.println("      judge new output against the reference. Ordering-only changes are");
    out.println("      excused via the O: hash; -prev (a manifest from a second same-commit");
    out.println("      build) upgrades the static allowlist to evidence-based excusal.");
    out.println("      Exits 1 if unexplained content diffs remain, 0 if parity is clean");
    out.println("  impact <prevManifest> <newManifest>");
    out.println("      list content-level changes vs a previous build (ordering-only churn");
    out.println("      excluded), plus '+ path' for added and '- path' for removed files");
  }

  // -- manifest generation ----------------------------------------------------------------

  /**
   * Hash every file under publishDir; returns 'HASH\trelative/path' lines sorted by path.
   * Hashing runs in parallel across files; the result order is deterministic.
   */
  public static List<String> generateManifest(File publishDir) throws IOException {
    File root = publishDir.getCanonicalFile();
    if (!root.isDirectory())
      throw new IOException("not a directory: " + publishDir);
    // the build embeds its absolute checkout path in published links (htmldiff/jira); make
    // manifests location-independent by normalizing the path (url-encoded and raw forms)
    String checkout = root.getParent();
    List<Pattern> extra = compileAll(Pattern.quote(urlEncode(checkout)), Pattern.quote(checkout));
    List<String> files = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root.toPath())) {
      walk.filter(Files::isRegularFile).forEach(p -> files.add(root.toPath().relativize(p).toString()));
    }
    Collections.sort(files);
    return files.parallelStream().map(rel -> hashLine(root, rel, extra)).collect(Collectors.toList());
  }

  private static String hashLine(File root, String rel, List<Pattern> extra) {
    File f = new File(root, rel);
    String low = rel.toLowerCase();
    String h;
    if (low.endsWith(".xlsx"))
      h = "X:" + xlsxSig(f);
    else if (endsWithAny(low, ARCHIVE_EXTS))
      h = "A:" + archiveSig(f);
    else if (endsWithAny(low, BINARY_EXTS))
      h = binarySig(f);
    else
      h = normHashOfFile(f, extra);
    return h + "\t" + rel;
  }

  private static String binarySig(File f) {
    try {
      return "B:" + hex16(sha256(Files.readAllBytes(f.toPath())));
    } catch (IOException e) {
      return "B:READ-ERROR";
    }
  }

  private static String normHashOfFile(File f, List<Pattern> extra) {
    byte[] data;
    try {
      data = Files.readAllBytes(f.toPath());
    } catch (IOException e) {
      return "N:READ-ERROR/O:READ-ERROR";
    }
    return normHash(data, extra);
  }

  /**
   * Returns 'N:&lt;exact&gt;/O:&lt;order-insensitive&gt;' for normalized text content. O hashes the
   * sorted multiset of trimmed non-empty lines, so pure line reordering compares equal under
   * O while any content change still flags.
   */
  public static String normHash(byte[] data, List<Pattern> extra) {
    byte[] d = applyPatterns(data, TS_PATTERNS);
    d = applyPatterns(d, extra);
    return "N:" + hex16(sha256(d)) + "/O:" + hex16(sha256(orderInsensitiveForm(d)));
  }

  /** sorted multiset of trimmed non-empty lines, joined with \n (byte-level, unsigned sort) */
  static byte[] orderInsensitiveForm(byte[] data) {
    List<byte[]> lines = new ArrayList<>();
    int start = 0;
    for (int i = 0; i <= data.length; i++) {
      if (i == data.length || data[i] == '\n') {
        int s = start, e = i;
        while (s < e && isAsciiSpace(data[s]))
          s++;
        while (e > s && isAsciiSpace(data[e - 1]))
          e--;
        if (e > s)
          lines.add(Arrays.copyOfRange(data, s, e));
        start = i + 1;
      }
    }
    lines.sort(Arrays::compareUnsigned);
    ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
    for (int i = 0; i < lines.size(); i++) {
      if (i > 0)
        out.write('\n');
      out.write(lines.get(i), 0, lines.get(i).length);
    }
    return out.toByteArray();
  }

  private static boolean isAsciiSpace(byte b) {
    return b == ' ' || b == '\t' || b == '\r' || b == '\n' || b == 0x0b || b == 0x0c;
  }

  /**
   * Deep hash of .xlsx member content with font-metric variance removed. Any real content
   * change still changes the hash.
   */
  static String xlsxSig(File f) {
    try (ZipFile z = new ZipFile(f)) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (ZipEntry e : sortedEntries(z)) {
        byte[] data = readAll(z.getInputStream(e));
        String s = new String(data, StandardCharsets.ISO_8859_1);
        s = XLSX_WIDTH.matcher(s).replaceAll("width=\"W\"");
        for (Pattern p : TS_PATTERNS)
          s = p.matcher(s).replaceAll("TS");
        md.update(e.getName().getBytes(StandardCharsets.UTF_8));
        md.update(s.getBytes(StandardCharsets.ISO_8859_1));
      }
      return hex16(md.digest());
    } catch (Exception ex) {
      return "XLSX-ERROR";
    }
  }

  /**
   * Sorted member listing (names + sizes for zips, names for .tgz): archives store mtimes
   * which always differ, and members are written in filesystem-enumeration order, which
   * differs across machines for identical content. Member add/remove/size changes still
   * change the hash.
   */
  static String archiveSig(File f) {
    try {
      List<String> lines = new ArrayList<>();
      if (f.getName().toLowerCase().endsWith(".tgz")) {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
            new GzipCompressorInputStream(new BufferedInputStream(Files.newInputStream(f.toPath()))))) {
          TarArchiveEntry e;
          while ((e = tar.getNextEntry()) != null)
            lines.add(e.getName());
        }
      } else {
        try (ZipFile z = new ZipFile(f)) {
          for (ZipEntry e : sortedEntries(z))
            lines.add(e.getSize() + " " + e.getName());
        }
      }
      Collections.sort(lines);
      return hex16(sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      return "ARCHIVE-ERROR";
    }
  }

  // -- compare (the judge) and impact -----------------------------------------------------

  public static class CompareResult {
    /** content-different paths not excused by evidence or the allowlist, sorted */
    public final List<String> unexplained = new ArrayList<>();
    /** candidates excused because they provably varied between this run's two builds */
    public int excused;
    /** all content-level diffs (before exclusions/excusals) */
    public int contentDiffs;
  }

  /**
   * The judge: a file (present in both manifests) is content-different when its hash fields
   * differ AND, for N:/O: entries, the order-insensitive O parts also differ - pure
   * reordering is excused. .shex/.shex.html/.xls files and all-valuesets.zip are always
   * excluded. If prev (a manifest from a second same-commit build) is supplied, files whose
   * hash differs between prev and nw are excused as evidence of nondeterminism this run;
   * allowlist members are excused in either mode.
   */
  public static CompareResult compare(Map<String, String> ref, Map<String, String> nw,
      Map<String, String> prev, Set<String> allowlist) {
    CompareResult res = new CompareResult();
    List<String> candidates = new ArrayList<>();
    for (Map.Entry<String, String> e : ref.entrySet()) {
      String nh = nw.get(e.getKey());
      if (nh != null && contentDifferent(e.getValue(), nh)) {
        res.contentDiffs++;
        if (!EXCLUDED_PATHS.matcher(e.getKey()).matches())
          candidates.add(e.getKey());
      }
    }
    Set<String> noisyNow = new HashSet<>();
    if (prev != null) {
      for (Map.Entry<String, String> e : prev.entrySet()) {
        String nh = nw.get(e.getKey());
        if (nh != null && !nh.equals(e.getValue()))
          noisyNow.add(e.getKey());
      }
    }
    for (String path : candidates) {
      if (prev != null && noisyNow.contains(path))
        res.excused++;
      else if (!allowlist.contains(path))
        res.unexplained.add(path);
    }
    Collections.sort(res.unexplained);
    return res;
  }

  /**
   * Content-level changes between two builds (ordering-only churn excluded), plus
   * '+ path' for added files and '- path' for removed ones.
   */
  public static List<String> impact(Map<String, String> prev, Map<String, String> nw) {
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, String> e : prev.entrySet()) {
      String nh = nw.get(e.getKey());
      if (nh != null && contentDifferent(e.getValue(), nh))
        out.add(e.getKey());
    }
    Collections.sort(out);
    List<String> added = new ArrayList<>();
    for (String path : nw.keySet())
      if (!prev.containsKey(path))
        added.add(path);
    Collections.sort(added);
    for (String path : added)
      out.add("+ " + path);
    List<String> removed = new ArrayList<>();
    for (String path : prev.keySet())
      if (!nw.containsKey(path))
        removed.add(path);
    Collections.sort(removed);
    for (String path : removed)
      out.add("- " + path);
    return out;
  }

  /**
   * True when the hash fields differ AND (for N:/O: entries) the O parts differ too -
   * a file whose exact (N:) hash differs but whose order-insensitive (O:) hash matches
   * changed only in element order, the documented stock nondeterminism class.
   */
  public static boolean contentDifferent(String refHash, String newHash) {
    if (refHash.equals(newHash))
      return false;
    int i = refHash.indexOf('/');
    if (i < 0)
      return true;
    int j = newHash.indexOf('/');
    return !refHash.substring(i + 1).equals(j < 0 ? "" : newHash.substring(j + 1));
  }

  /** parse 'HASH\trelative/path' lines into path -> hash */
  public static Map<String, String> readManifest(File f) throws IOException {
    Map<String, String> map = new LinkedHashMap<>();
    for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
      int tab = line.indexOf('\t');
      if (tab > 0)
        map.put(line.substring(tab + 1), line.substring(0, tab));
    }
    return map;
  }

  private static Set<String> readAllowlist(File f) throws IOException {
    Set<String> set = new HashSet<>();
    for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8))
      if (!line.isEmpty())
        set.add(line);
    return set;
  }

  // -- helpers ----------------------------------------------------------------------------

  /**
   * Byte-level regex application: the data is treated as ISO-8859-1 (a 1:1 byte/char
   * mapping), every match is replaced with the literal bytes "TS", and the result is
   * converted back to bytes. All patterns are pure ASCII so this is equivalent to matching
   * over the raw UTF-8 bytes.
   */
  static byte[] applyPatterns(byte[] data, List<Pattern> patterns) {
    if (patterns.isEmpty())
      return data;
    String s = new String(data, StandardCharsets.ISO_8859_1);
    for (Pattern p : patterns)
      s = p.matcher(s).replaceAll("TS");
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  /** url-encode like python's urllib.parse.quote(s, safe=''): everything outside
   *  [A-Za-z0-9._~-] becomes %XX (uppercase hex) per UTF-8 byte */
  static String urlEncode(String s) {
    StringBuilder b = new StringBuilder();
    for (byte by : s.getBytes(StandardCharsets.UTF_8)) {
      int c = by & 0xFF;
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
          || c == '-' || c == '.' || c == '_' || c == '~')
        b.append((char) c);
      else
        b.append('%').append(String.format("%02X", c));
    }
    return b.toString();
  }

  private static List<ZipEntry> sortedEntries(ZipFile z) {
    List<ZipEntry> entries = new ArrayList<>();
    for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements();)
      entries.add(en.nextElement());
    entries.sort((a, b) -> a.getName().compareTo(b.getName()));
    return entries;
  }

  private static byte[] readAll(InputStream in) throws IOException {
    try (InputStream is = in) {
      return is.readAllBytes();
    }
  }

  private static byte[] sha256(byte[] data) {
    return sha256().digest(data);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new Error(e); // every JVM ships SHA-256
    }
  }

  static String hex16(byte[] digest) {
    StringBuilder b = new StringBuilder(16);
    for (int i = 0; i < 8; i++)
      b.append(String.format("%02x", digest[i]));
    return b.toString();
  }

  private static boolean endsWithAny(String s, String[] exts) {
    for (String e : exts)
      if (s.endsWith(e))
        return true;
    return false;
  }

  private static List<Pattern> compileAll(String... patterns) {
    List<Pattern> list = new ArrayList<>();
    for (String p : patterns)
      list.add(Pattern.compile(p));
    return Collections.unmodifiableList(list);
  }

}

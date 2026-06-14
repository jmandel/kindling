package org.hl7.fhir.tools.publisher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hl7.fhir.r5.terminologies.utilities.TxLock;

/**
 * The one front door for the fhir.lock-driven ("future world") spec build and its verification
 * tools - a single, OS-independent command surface replacing shell scripting:
 *
 * <pre>
 *   java -Xmx12g -cp kindling.jar org.hl7.fhir.tools.publisher.SpecBuild &lt;command&gt; ...
 *
 *   build [folder] [--online] [--judge] [--manifest] [--impact]
 *       Lock-driven build. Hermetic by default when the folder has a fhir.lock (any terminology
 *       network attempt fails loudly); --online lets pack misses fall through to the server
 *       (use when your change introduces genuinely new codes). After a successful build the
 *       output signature is checked against the lock's expectedSignature.
 *         --manifest  also fingerprint the published output (enables later judge/impact)
 *         --judge     verify the output against the committed reference manifest
 *         --impact    list exactly which published files changed vs your previous build
 *   manifest &lt;publishDir&gt;                          fingerprint a publish directory
 *   compare &lt;ref&gt; &lt;new&gt; [-prev m] [-allowlist f]   judge two manifests
 *   impact &lt;prev&gt; &lt;new&gt;                            content-level diff of two manifests
 *   diff-packs &lt;old&gt; &lt;new&gt;                         canonical answer-pack comparison
 * </pre>
 *
 * The build pins its environment programmatically (locale en-US, timezone America/Chicago -
 * both leak into pack request keys and published bytes otherwise), so no JVM flags beyond heap
 * are required. Heap below ~11GB is detected and warned about with the exact flag to add.
 */
public class SpecBuild {

  private static final Locale PINNED_LOCALE = Locale.US;
  private static final String PINNED_TIMEZONE = "America/Chicago";
  private static final long MIN_HEAP_BYTES = 10L * 1024 * 1024 * 1024;
  private static final Pattern SIGNATURE = Pattern.compile("Summary: Errors=(\\d+), Warnings=(\\d+), Information messages=(\\d+)");

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      usage();
      System.exit(2);
    }
    String cmd = args[0];
    String[] rest = Arrays.copyOfRange(args, 1, args.length);
    switch (cmd) {
    case "build":
      System.exit(build(rest));
      break;
    case "manifest":
    case "compare":
    case "impact":
      OutputManifest.main(args); // shares this jar; exits via return below
      break;
    case "diff-packs":
      org.hl7.fhir.r5.terminologies.utilities.TerminologyCachePackager.main(
          prepend("diff", rest));
      break;
    case "help":
    case "--help":
    case "-h":
      usage();
      break;
    default:
      System.err.println("unknown command: " + cmd);
      usage();
      System.exit(2);
    }
  }

  private static String[] prepend(String first, String[] rest) {
    String[] all = new String[rest.length + 1];
    all[0] = first;
    System.arraycopy(rest, 0, all, 1, rest.length);
    return all;
  }

  private static int build(String[] args) throws Exception {
    String folder = ".";
    boolean online = false, judge = false, manifest = false, impact = false;
    List<String> publisherArgs = new ArrayList<>(Arrays.asList("-nosound", "-nopartial"));
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      switch (a) {
      case "--online":   online = true; break;
      case "--hermetic": online = false; break;
      case "--judge":    judge = true; manifest = true; break;
      case "--manifest": manifest = true; break;
      case "--impact":   impact = true; manifest = true; break;
      case "-fhir-settings":
        publisherArgs.add(a); publisherArgs.add(args[++i]); break;
      default:
        if (a.startsWith("-")) {
          publisherArgs.add(a);
        } else {
          folder = a;
        }
      }
    }
    File root = new File(folder).getCanonicalFile();
    File lock = new File(root, "fhir.lock");
    if (!lock.exists()) {
      System.err.println("no fhir.lock in " + root + " - this command drives lock-pinned builds; use the stock publisher otherwise");
      return 2;
    }
    if (new File(root, "tools/build/fhir-settings.json").exists() && !publisherArgs.contains("-fhir-settings")) {
      publisherArgs.add("-fhir-settings");
      publisherArgs.add(new File(root, "tools/build/fhir-settings.json").getAbsolutePath());
    }

    // the pinned configuration, applied in-process: both leak into output/request keys otherwise
    Locale.setDefault(PINNED_LOCALE);
    TimeZone.setDefault(TimeZone.getTimeZone(PINNED_TIMEZONE));
    System.setProperty("file.encoding", "UTF-8");
    if (System.getProperty("org.hl7.fhir.tx.maxConcurrency") == null) {
      System.setProperty("org.hl7.fhir.tx.maxConcurrency", "12");
    }
    if (System.getProperty("org.hl7.fhir.tx.localFirst") == null) {
      System.setProperty("org.hl7.fhir.tx.localFirst", "true");
    }
    File missLog = null;
    if (!online) {
      System.setProperty("org.hl7.fhir.tx.hermetic", "true");
      System.out.println("hermetic: any terminology network attempt is a hard failure (use --online when adding new codes)");
    } else if (System.getProperty("org.hl7.fhir.tx.logMisses") == null) {
      // count the questions the pack could not answer, so the build (and CI) can report
      // "this change introduces N new terminology questions" as a signal
      missLog = File.createTempFile("tx-misses", ".ndjson");
      missLog.delete();
      System.setProperty("org.hl7.fhir.tx.logMisses", missLog.getAbsolutePath());
    }
    if (Runtime.getRuntime().maxMemory() < MIN_HEAP_BYTES) {
      System.out.println("WARNING: max heap is " + (Runtime.getRuntime().maxMemory() >> 20)
          + "MB; the spec build wants ~12GB - add e.g. -Xmx12g before -cp");
    }

    File logFile = new File(root, "build-future.log");
    File manifestFile = new File(root, "build-future.manifest");
    File prevManifest = null;
    if (manifest && manifestFile.exists()) {
      prevManifest = File.createTempFile("prev-manifest", ".txt");
      Files.copy(manifestFile.toPath(), new FileOutputStream(prevManifest));
    }

    long start = System.currentTimeMillis();
    PrintStream original = System.out;
    String[] signature = new String[1];
    try (FileOutputStream logStream = new FileOutputStream(logFile);
         PrintStream tee = new PrintStream(new TeeStream(original, logStream, signature), true, "UTF-8")) {
      System.setOut(tee);
      try {
        // delegate to Publisher.main: it owns the (substantial) PageProcessor/flag setup.
        // On build failure it exits the process directly, which is correct CLI behavior
        List<String> all = new ArrayList<>(publisherArgs);
        all.add("-folder");
        all.add(root.getAbsolutePath());
        Publisher.main(all.toArray(new String[0]));
      } finally {
        System.setOut(original);
      }
    } catch (Exception e) {
      System.out.println("BUILD FAILED after " + ((System.currentTimeMillis() - start) / 1000) + "s: " + e.getMessage());
      throw e;
    }
    System.out.println("BUILD ok, duration=" + ((System.currentTimeMillis() - start) / 1000) + "s");
    if (missLog != null) {
      long misses = missLog.exists() ? Files.lines(missLog.toPath()).count() : 0;
      System.out.println("terminology questions not answered by the pack: " + misses
          + (misses == 0 ? "" : " (these will fold into the pack at the next refresh)"));
      missLog.delete();
    }

    int[] expected = TxLock.expectedSignature(lock.getAbsolutePath());
    if (expected != null) {
      if (signature[0] == null) {
        System.err.println("no output signature found in the build log");
        return 1;
      }
      String want = "Summary: Errors=" + expected[0] + ", Warnings=" + expected[1] + ", Information messages=" + expected[2];
      System.out.println("signature: " + signature[0] + " (expected " + want.substring("Summary: ".length()) + ")");
      if (!signature[0].equals(want)) {
        if ("report".equals(System.getProperty("org.hl7.fhir.spec.signatureGate", System.getenv().getOrDefault("SIGNATURE_GATE", "enforce")))) {
          System.out.println("SIGNATURE CHANGED (reported, not enforced)");
        } else {
          System.err.println("OUTPUT SIGNATURE MISMATCH");
          return 1;
        }
      }
    }

    File publish = new File(root, "publish");
    if (manifest) {
      try (PrintStream out = new PrintStream(new FileOutputStream(manifestFile), false, "UTF-8")) {
        for (String mline : OutputManifest.generateManifest(publish)) {
          out.println(mline);
        }
      }
    }
    if (impact) {
      if (prevManifest == null) {
        System.out.println("impact: no previous build manifest found (run 'build --manifest' first)");
      } else {
        System.out.println("== impact: published files changed vs your previous build");
        OutputManifest.run(new String[] { "impact", prevManifest.getAbsolutePath(), manifestFile.getAbsolutePath() }, System.out);
      }
    }
    if (judge) {
      System.out.println("== judging published output against the committed reference manifest");
      List<String> cmp = new ArrayList<>(Arrays.asList("compare",
          new File(root, "tools/build/ref.manifest").getAbsolutePath(), manifestFile.getAbsolutePath(),
          "-allowlist", new File(root, "tools/build/noise-files-v2.txt").getAbsolutePath()));
      if (prevManifest != null) {
        cmp.add("-prev");
        cmp.add(prevManifest.getAbsolutePath());
      }
      int rc = OutputManifest.run(cmp.toArray(new String[0]), System.out);
      if (rc != 0) {
        return rc;
      }
    }
    return 0;
  }

  /** tees build output to the console and the log file while watching for the signature line */
  private static class TeeStream extends OutputStream {
    private final PrintStream console;
    private final OutputStream log;
    private final String[] signature;
    private final StringBuilder line = new StringBuilder();

    TeeStream(PrintStream console, OutputStream log, String[] signature) {
      this.console = console;
      this.log = log;
      this.signature = signature;
    }

    @Override
    public void write(int b) throws IOException {
      console.write(b);
      log.write(b);
      if (b == '\n') {
        Matcher m = SIGNATURE.matcher(line);
        if (m.find()) {
          signature[0] = m.group();
        }
        line.setLength(0);
      } else if (line.length() < 4096) {
        line.append((char) b);
      }
    }

    @Override
    public void flush() throws IOException {
      console.flush();
      log.flush();
    }
  }

  private static void usage() {
    System.out.println("Usage: SpecBuild <build|manifest|compare|impact|diff-packs|help> ...");
    System.out.println("see the class javadoc / FUTURE.md for details");
  }
}

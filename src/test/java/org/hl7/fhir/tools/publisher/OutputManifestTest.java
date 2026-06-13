package org.hl7.fhir.tools.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class OutputManifestTest {

  private static String normHash(String content) {
    return OutputManifest.normHash(content.getBytes(StandardCharsets.UTF_8), Collections.emptyList());
  }

  @Test
  public void testTimestampAndUuidNormalization() {
    String a = "<p>Generated 2026-06-11T09:45:07.123-05:00 id=\"0a1b2c3d-1111-2222-3333-444455556666\""
        + " on Thu, Jun 11, 2026 09:45-0500 by Local Build (jmandel) at 11 Jun 2026</p>";
    String b = "<p>Generated 2024-01-02 23:59:59Z id=\"ffffffff-aaaa-bbbb-cccc-dddddddddddd\""
        + " on Mon, Jan 2, 2024 23:59 by Local Build (someoneelse) at 2 Jan 2024</p>";
    assertEquals(normHash(a), normHash(b));
    // a real word change still flags
    String tampered = a.replace("Generated", "Genersted");
    assertNotEquals(normHash(a), normHash(tampered));
  }

  @Test
  public void testOrderInsensitiveTier() {
    String a = normHash("alpha\n  beta\ngamma\n");
    String b = normHash("gamma\nalpha\nbeta  \n\n");
    // exact (N:) hashes differ, order-insensitive (O:) hashes match -> excused as reordering
    assertNotEquals(nPart(a), nPart(b));
    assertEquals(oPart(a), oPart(b));
    assertFalse(OutputManifest.contentDifferent(a, b));
    // a tampered line differs under O too -> flagged
    String c = normHash("gamma\nalpha\nbetX\n");
    assertNotEquals(oPart(a), oPart(c));
    assertTrue(OutputManifest.contentDifferent(a, c));
    // single-field tiers (X:/A:/B:) flag on any difference
    assertTrue(OutputManifest.contentDifferent("B:0123456789abcdef", "B:fedcba9876543210"));
    assertFalse(OutputManifest.contentDifferent("B:0123456789abcdef", "B:0123456789abcdef"));
  }

  @Test
  public void testCompareExclusionsAndAllowlist(@TempDir Path tmp) throws IOException {
    Map<String, String> ref = new LinkedHashMap<>();
    Map<String, String> nw = new LinkedHashMap<>();
    // always-excluded paths
    put(ref, nw, "account.shex", "N:aaaa/O:aaaa", "N:bbbb/O:bbbb");
    put(ref, nw, "account.shex.html", "N:aaaa/O:aaaa", "N:bbbb/O:bbbb");
    put(ref, nw, "old-format.xls", "B:aaaa", "B:bbbb");
    put(ref, nw, "all-valuesets.zip", "A:aaaa", "A:bbbb");
    // ordering-only change: excused by the O hash
    put(ref, nw, "reordered.html", "N:aaaa/O:same", "N:bbbb/O:same");
    // unchanged
    put(ref, nw, "stable.html", "N:aaaa/O:aaaa", "N:aaaa/O:aaaa");
    // real content changes
    put(ref, nw, "allowlisted.html", "N:aaaa/O:aaaa", "N:bbbb/O:bbbb");
    put(ref, nw, "tampered.html", "N:aaaa/O:aaaa", "N:bbbb/O:bbbb");
    // present only in ref: the judge only considers files present in both manifests
    ref.put("removed.html", "N:aaaa/O:aaaa");

    OutputManifest.CompareResult res = OutputManifest.compare(ref, nw, null, Set.of("allowlisted.html"));
    assertEquals(List.of("tampered.html"), res.unexplained);
    assertEquals(6, res.contentDiffs); // everything but stable/reordered/removed
    assertEquals(0, res.excused);

    // evidence-based excusal: tampered.html varied between this run's two builds -> excused
    Map<String, String> prev = new LinkedHashMap<>(nw);
    prev.put("tampered.html", "N:cccc/O:cccc");
    res = OutputManifest.compare(ref, nw, prev, Collections.emptySet());
    assertEquals(1, res.excused);
    assertEquals(List.of("allowlisted.html"), res.unexplained);

    // round-trip through the CLI file format
    File refFile = tmp.resolve("ref.manifest").toFile();
    Files.write(refFile.toPath(), render(ref).getBytes(StandardCharsets.UTF_8));
    assertEquals(ref, OutputManifest.readManifest(refFile));
  }

  @Test
  public void testManifestGenerationAndSelfCompare(@TempDir Path tmp) throws IOException {
    Path pub = tmp.resolve("publish");
    Files.createDirectories(pub.resolve("sub"));
    Files.write(pub.resolve("a.html"), ("<p>built 2026-06-11T09:45:07Z under "
        + pub.toFile().getCanonicalFile().getParent() + "</p>").getBytes(StandardCharsets.UTF_8));
    Files.write(pub.resolve("sub").resolve("b.txt"), "two\nlines\n".getBytes(StandardCharsets.UTF_8));
    Files.write(pub.resolve("c.png"), new byte[] { 1, 2, 3 });
    List<String> lines = OutputManifest.generateManifest(pub.toFile());
    assertEquals(3, lines.size());
    assertTrue(lines.get(0).matches("N:[0-9a-f]{16}/O:[0-9a-f]{16}\ta\\.html"));
    assertTrue(lines.get(1).matches("B:[0-9a-f]{16}\tc\\.png"));
    assertTrue(lines.get(2).matches("N:[0-9a-f]{16}/O:[0-9a-f]{16}\tsub/b\\.txt"));
    // the checkout path (parent of publish) is normalized out of hashed content: moving the
    // tree must not change the manifest
    Path pub2 = tmp.resolve("elsewhere").resolve("publish");
    Files.createDirectories(pub2.resolve("sub"));
    Files.write(pub2.resolve("a.html"), ("<p>built 2025-01-01 11:22:33+05:30 under "
        + pub2.toFile().getCanonicalFile().getParent() + "</p>").getBytes(StandardCharsets.UTF_8));
    Files.write(pub2.resolve("sub").resolve("b.txt"), "two\nlines\n".getBytes(StandardCharsets.UTF_8));
    Files.write(pub2.resolve("c.png"), new byte[] { 1, 2, 3 });
    assertEquals(lines, OutputManifest.generateManifest(pub2.toFile()));
  }

  private static String nPart(String hash) {
    return hash.substring(0, hash.indexOf('/'));
  }

  private static String oPart(String hash) {
    return hash.substring(hash.indexOf('/') + 1);
  }

  private static void put(Map<String, String> ref, Map<String, String> nw, String path, String refHash, String newHash) {
    ref.put(path, refHash);
    nw.put(path, newHash);
  }

  private static String render(Map<String, String> manifest) {
    StringBuilder b = new StringBuilder();
    for (Map.Entry<String, String> e : manifest.entrySet())
      b.append(e.getValue()).append('\t').append(e.getKey()).append('\n');
    return b.toString();
  }

}

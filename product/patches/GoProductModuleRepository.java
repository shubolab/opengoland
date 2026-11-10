/*
 * Independent packaging helper using the pinned open-source IntelliJ runtime
 * repository API. This removes available-module inventory entries; it does not
 * replace filtering the separately serialized bundled-plugin descriptors.
 */
import com.intellij.platform.runtime.repository.RuntimeModuleId;
import com.intellij.platform.runtime.repository.serialization.RawRuntimeModuleDescriptor;
import com.intellij.platform.runtime.repository.serialization.RawRuntimeModuleRepositoryData;
import com.intellij.platform.runtime.repository.serialization.RuntimeModuleRepositorySerialization;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;

public final class GoProductModuleRepository {
  private static final String INPUT_DAT_SHA256 = "ccfbc2d268c4550c028bfeaf3edd411d14769c6943a18685a476605d92e01d53";
  private static final String INPUT_JAR_SHA256 = "2f453eda0ae0a2f690fab75db89130854927893e7ae17a759ad677af0501c2f8";
  private static final String BOOTSTRAP = "intellij.platform.bootstrap";
  private static final Set<String> EXCLUDED = Set.of(
    "intellij.java.ide.customization", "intellij.java.ide.customization.training",
    "intellij.java.promo",
    "intellij.settingsSync.core", "intellij.vcs.github.ultimatePromo",
    "intellij.grazie", "intellij.grazie.core", "intellij.grazie.java",
    "intellij.grazie.json", "intellij.grazie.markdown", "intellij.grazie.properties",
    "intellij.grazie.xml", "intellij.grazie.yaml",
    "intellij.libraries.ai.grazie.spell.gec.engine.local", "lib.ai.grazie.rule.engine",
    "intellij.toml.grazie", "intellij.kotlin.grazie");
  // The pinned Maven plugin declares tanvd.grazi as an optional legacy
  // integration, with only its Grazie-specific problem filter in that file.
  // Kotlin's K1/K2 descriptors separately register the optional Grazie content
  // module. Removing these integrations must retain Maven and Kotlin themselves.
  private record OptionalEdge(String module, String dependency, String beforeSha256, String afterSha256) {
    String entry() { return module + ".xml"; }
  }
  private static final List<OptionalEdge> OPTIONAL_EDGES = List.of(
    new OptionalEdge("intellij.maven", "intellij.grazie.core",
      "1d65cdd72b5640fe11f611ad217af5874fe19ec79a47c714082c8161bf50b91b",
      "d3da1ae75c61f2e6d98983f7eb2d87f543e4b53962a8610336514111344ca2a2"),
    new OptionalEdge("kotlin.plugin.k1", "intellij.kotlin.grazie",
      "5805a6155812b4fe8eacc911b0c8afb1cf39c534e25f12684264e9bca126c911",
      "1d35f3f5362665a1d922bdfc086977c2a5c4b9fc8944a7cc29814a8a3be63d4a"),
    new OptionalEdge("kotlin.plugin.k2", "intellij.kotlin.grazie",
      "daadd1cd8b64db114a21f3dc6a7f815b4a3dbc0ab201ecf93f9d220809b05545",
      "1853feea3639dd7e36defcc30838035ef5e31c9cdace88f68e35de0cb134f894"));
  private static final String RETAINED_LOGGING = "lib.io.github.oshai.kotlin.logging.jvm";

  private record Descriptor(List<String> dependencies, List<String> resourcePaths) {}
  private record CompactHeader(int formatVersion, int generatorVersion, String bootstrapModule,
                               List<String> bootstrapClasspath, int descriptors, List<String> unresolvedIds) {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0 || !(args[0].equals("filter") || args[0].equals("verify"))) {
      throw new IllegalArgumentException("Use filter|verify --input-dat PATH --input-jar PATH --output-dat PATH --owned-root PATH [--output-jar PATH; required for verify]");
    }
    boolean verify = args[0].equals("verify");
    Map<String, String> options = options(args);
    boolean withOutputJar = verify || options.containsKey("--output-jar");
    Set<String> expectedOptions = withOutputJar
      ? Set.of("--input-dat", "--input-jar", "--output-dat", "--output-jar", "--owned-root")
      : Set.of("--input-dat", "--input-jar", "--output-dat", "--owned-root");
    require(options.keySet().equals(expectedOptions), "Unexpected or missing helper arguments");
    Path inputDat = regularFile(Path.of(options.get("--input-dat")));
    Path inputJar = regularFile(Path.of(options.get("--input-jar")));
    require(Files.size(inputDat) == 152042 && sha256(inputDat).equals(INPUT_DAT_SHA256), "Pinned input compact repository differs");
    require(Files.size(inputJar) == 442712 && sha256(inputJar).equals(INPUT_JAR_SHA256), "Pinned input XML repository differs");

    Path sourceSdkContents = Path.of(RuntimeModuleRepositorySerialization.class.getProtectionDomain()
      .getCodeSource().getLocation().toURI()).toRealPath().getParent().getParent();
    boolean macProductInfo = Files.isRegularFile(sourceSdkContents.resolve("Resources/product-info.json"));
    boolean flatProductInfo = Files.isRegularFile(sourceSdkContents.resolve("product-info.json"));
    require(Files.isDirectory(sourceSdkContents.resolve("lib"))
      && macProductInfo != flatProductInfo, "Cannot identify one native source SDK layout from the public API class origin");
    Path ownedRoot = ownedRoot(Path.of(options.get("--owned-root")), sourceSdkContents);
    Path outputDat = ownedPath(ownedRoot, Path.of(options.get("--output-dat")), verify);
    require(!outputDat.equals(inputDat) && !outputDat.equals(inputJar), "Output must not overwrite an input");
    Path outputJar = withOutputJar ? ownedPath(ownedRoot, Path.of(options.get("--output-jar")), verify) : null;
    if (outputJar != null) {
      require(!outputJar.equals(inputDat) && !outputJar.equals(inputJar) && !outputJar.equals(outputDat), "Output repositories must be separate from their inputs and each other");
    }

    RawRuntimeModuleRepositoryData original = RuntimeModuleRepositorySerialization.loadFromCompactFile(inputDat);
    Map<String, Descriptor> originalGraph = graph(original);
    require(originalGraph.equals(graph(RuntimeModuleRepositorySerialization.loadFromJar(inputJar))), "Original compact/XML repository graphs differ");
    CompactHeader before = compactHeader(inputDat);
    require(before.formatVersion == 2 && before.generatorVersion == 3 && before.descriptors == 1087
      && before.unresolvedIds.size() == 7 && BOOTSTRAP.equals(before.bootstrapModule)
      && before.bootstrapClasspath.size() == 121, "Unexpected pinned compact repository metadata");
    require(originalGraph.keySet().containsAll(EXCLUDED), "Expected excluded modules are missing");
    require(originalGraph.containsKey(RETAINED_LOGGING), "The shared logging module must remain available");
    for (OptionalEdge edge : OPTIONAL_EDGES) {
      Descriptor optional = originalGraph.get(edge.module);
      require(optional != null && java.util.Collections.frequency(optional.dependencies, edge.dependency) == 1,
        "Expected exactly one pinned optional integration edge: " + edge.module);
    }

    List<RawRuntimeModuleDescriptor> remaining = new ArrayList<>();
    Map<String, Descriptor> expectedGraph = new TreeMap<>(originalGraph);
    EXCLUDED.forEach(expectedGraph::remove);
    for (OptionalEdge edge : OPTIONAL_EDGES) {
      Descriptor optional = originalGraph.get(edge.module);
      expectedGraph.put(edge.module, new Descriptor(optional.dependencies.stream()
        .filter(id -> !edge.dependency.equals(id)).toList(), optional.resourcePaths));
    }
    for (RuntimeModuleId id : original.getAllModuleIds()) {
      if (!EXCLUDED.contains(id.getStringId())) {
        RawRuntimeModuleDescriptor descriptor = original.findDescriptor(id);
        require(descriptor != null, "Repository lost module " + id.getStringId());
        OptionalEdge optional = OPTIONAL_EDGES.stream().filter(edge -> edge.module.equals(id.getStringId())).findFirst().orElse(null);
        if (optional != null) {
          descriptor = RawRuntimeModuleDescriptor.create(id, descriptor.getResourcePaths(), descriptor.getDependencyIds().stream()
            .filter(dependency -> !optional.dependency.equals(dependency.getStringId())).toList());
        }
        for (RuntimeModuleId dependency : descriptor.getDependencyIds()) {
          require(!EXCLUDED.contains(dependency.getStringId()), "Remaining module " + id.getStringId() + " depends on an excluded module");
        }
        remaining.add(descriptor);
      }
    }
    require(remaining.size() == 1087 - EXCLUDED.size(), "Unexpected filtered descriptor count");

    if (!verify) {
      Path temporary = Files.createTempFile(outputDat.getParent(), ".opengoland-modules-", ".dat");
      Path temporaryJar = outputJar == null ? null : Files.createTempFile(outputJar.getParent(), ".opengoland-modules-", ".jar");
      try {
        RuntimeModuleRepositorySerialization.saveToCompactFile(remaining, BOOTSTRAP, temporary, before.generatorVersion);
        verifyCompact(temporary, expectedGraph, before);
        if (temporaryJar != null) {
          writeFilteredJar(inputJar, temporaryJar);
          require(expectedGraph.equals(graph(RuntimeModuleRepositorySerialization.loadFromJar(temporaryJar))),
            "Generated XML repository graph differs from the expected graph");
          verifyJarEntries(inputJar, temporaryJar);
        }
        Files.move(temporary, outputDat, StandardCopyOption.ATOMIC_MOVE);
        if (temporaryJar != null) Files.move(temporaryJar, outputJar, StandardCopyOption.ATOMIC_MOVE);
      }
      finally {
        Files.deleteIfExists(temporary);
        if (temporaryJar != null) Files.deleteIfExists(temporaryJar);
      }
    }
    CompactHeader after = verifyCompact(outputDat, expectedGraph, before);
    if (outputJar != null) {
      require(expectedGraph.equals(graph(RuntimeModuleRepositorySerialization.loadFromJar(outputJar))), "Filtered XML repository graph differs from the expected graph");
      require(Arrays.equals(before.bootstrapClasspath.toArray(String[]::new),
        RuntimeModuleRepositorySerialization.loadBootstrapClasspath(outputJar, BOOTSTRAP)), "Filtered XML repository bootstrap classpath differs");
      verifyJarEntries(inputJar, outputJar);
    }

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("operation", args[0]);
    report.put("inputDatSha256", INPUT_DAT_SHA256);
    report.put("inputJarSha256", INPUT_JAR_SHA256);
    report.put("inputGraphsEqual", true);
    report.put("removedModuleIds", new TreeSet<>(EXCLUDED));
    report.put("descriptorsBefore", before.descriptors);
    report.put("descriptorsAfter", after.descriptors);
    report.put("formatVersion", after.formatVersion);
    report.put("generatorVersion", after.generatorVersion);
    report.put("bootstrapModule", after.bootstrapModule);
    report.put("bootstrapClasspathCount", after.bootstrapClasspath.size());
    report.put("bootstrapClasspathPreserved", true);
    report.put("unresolvedDependenciesBefore", before.unresolvedIds.size());
    report.put("unresolvedDependenciesAfter", after.unresolvedIds.size());
    report.put("unresolvedDependencyIds", after.unresolvedIds);
    report.put("removedUnresolvedDependencyIds", before.unresolvedIds.stream().filter(id -> !after.unresolvedIds.contains(id)).toList());
    report.put("remainingDescriptorSemanticsPreservedExceptDeclaredOptionalEdges", true);
    report.put("removedOptionalEdges", OPTIONAL_EDGES.stream().map(edge -> Map.of("module", edge.module, "dependency", edge.dependency)).toList());
    report.put("retainedSharedLoggingModule", RETAINED_LOGGING);
    report.put("outputDat", outputDat.toString());
    report.put("outputDatBytes", Files.size(outputDat));
    report.put("outputDatSha256", sha256(outputDat));
    if (outputJar != null) {
      report.put("outputJar", outputJar.toString());
      report.put("outputJarSha256", sha256(outputJar));
      report.put("outputGraphsEqual", true);
      report.put("remainingJarEntryContentsPreservedExceptDeclaredOptionalEdges", true);
      report.put("changedJarEntries", OPTIONAL_EDGES.stream().map(edge -> Map.of("entry", edge.entry(),
        "beforeSha256", edge.beforeSha256, "afterSha256", edge.afterSha256)).toList());
    }
    System.out.println(json(report));
  }

  private static CompactHeader verifyCompact(Path path, Map<String, Descriptor> expected, CompactHeader before) throws IOException {
    require(expected.equals(graph(RuntimeModuleRepositorySerialization.loadFromCompactFile(path))), "Filtered compact repository graph differs from the expected graph");
    CompactHeader after = compactHeader(path);
    require(after.formatVersion == before.formatVersion && after.generatorVersion == before.generatorVersion
      && after.descriptors == expected.size() && after.bootstrapModule.equals(before.bootstrapModule)
      && after.bootstrapClasspath.equals(before.bootstrapClasspath)
      && after.unresolvedIds.equals(expectedUnresolved(expected, before)), "Filtered compact repository metadata differs");
    require(Arrays.equals(before.bootstrapClasspath.toArray(String[]::new),
      RuntimeModuleRepositorySerialization.loadBootstrapClasspath(path, BOOTSTRAP)), "Public compact bootstrap API differs");
    return after;
  }

  private static List<String> expectedUnresolved(Map<String, Descriptor> graph, CompactHeader before) {
    Set<String> missing = new TreeSet<>();
    for (Descriptor descriptor : graph.values()) {
      for (String id : descriptor.dependencies) if (!graph.containsKey(id)) missing.add(id);
    }
    require(before.unresolvedIds.containsAll(missing), "Filtering introduced a missing module dependency");
    return before.unresolvedIds.stream().filter(missing::contains).toList();
  }

  private static void verifyJarEntries(Path original, Path filtered) throws IOException {
    try (ZipFile input = new ZipFile(original.toFile()); ZipFile output = new ZipFile(filtered.toFile())) {
      Map<String, byte[]> originals = new TreeMap<>();
      var entries = input.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (!EXCLUDED.contains(entry.getName().replaceFirst("\\.xml$", ""))) {
          try (var stream = input.getInputStream(entry)) {
            originals.put(entry.getName(), filteredEntryBytes(entry.getName(), stream.readAllBytes()));
          }
        }
      }
      Set<String> seen = new TreeSet<>();
      var filteredEntries = output.entries();
      while (filteredEntries.hasMoreElements()) {
        ZipEntry entry = filteredEntries.nextElement();
        require(seen.add(entry.getName()), "Duplicate filtered XML repository entry");
        require(originals.containsKey(entry.getName()), "Unexpected filtered XML repository entry: " + entry.getName());
        try (var stream = output.getInputStream(entry)) {
          require(Arrays.equals(originals.get(entry.getName()), stream.readAllBytes()), "Filtered XML repository changed retained entry: " + entry.getName());
        }
      }
      require(seen.equals(originals.keySet()), "Filtered XML repository lost a retained entry");
    }
  }

  private static byte[] filteredEntryBytes(String name, byte[] original) throws IOException {
    OptionalEdge edge = OPTIONAL_EDGES.stream().filter(candidate -> candidate.entry().equals(name)).findFirst().orElse(null);
    if (edge == null) return original;
    require(java.util.HexFormat.of().formatHex(digest(original)).equals(edge.beforeSha256),
      "Pinned optional-integration XML descriptor differs: " + name);
    String xml = new String(original, StandardCharsets.UTF_8);
    String line = "    <module name=\"" + edge.dependency + "\"/>\n";
    int offset = xml.indexOf(line);
    require(offset >= 0 && xml.indexOf(line, offset + line.length()) < 0, "Expected exactly one optional XML edge: " + name);
    byte[] filtered = (xml.substring(0, offset) + xml.substring(offset + line.length())).getBytes(StandardCharsets.UTF_8);
    require(java.util.HexFormat.of().formatHex(digest(filtered)).equals(edge.afterSha256),
      "Filtered optional-integration XML descriptor differs: " + name);
    return filtered;
  }

  private static void writeFilteredJar(Path original, Path target) throws IOException {
    try (ZipFile input = new ZipFile(original.toFile()); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
      if (input.getComment() != null) output.setComment(input.getComment());
      var entries = input.entries();
      Set<String> seen = new TreeSet<>();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        require(seen.add(entry.getName()), "Duplicate input XML repository entry");
        if (EXCLUDED.contains(entry.getName().replaceFirst("\\.xml$", ""))) continue;
        byte[] data;
        try (var stream = input.getInputStream(entry)) { data = filteredEntryBytes(entry.getName(), stream.readAllBytes()); }
        ZipEntry copied = new ZipEntry(entry.getName());
        copied.setMethod(entry.getMethod());
        copied.setTime(entry.getTime());
        if (entry.getComment() != null) copied.setComment(entry.getComment());
        if (entry.getExtra() != null) copied.setExtra(entry.getExtra());
        if (entry.getMethod() == ZipEntry.STORED) {
          CRC32 crc = new CRC32(); crc.update(data);
          copied.setSize(data.length); copied.setCompressedSize(data.length); copied.setCrc(crc.getValue());
        }
        output.putNextEntry(copied); output.write(data); output.closeEntry();
      }
    }
  }

  private static Map<String, Descriptor> graph(RawRuntimeModuleRepositoryData data) {
    Map<String, Descriptor> result = new TreeMap<>();
    for (RuntimeModuleId id : data.getAllModuleIds()) {
      RawRuntimeModuleDescriptor descriptor = data.findDescriptor(id);
      require(descriptor != null, "Missing module descriptor");
      result.put(id.getStringId(), new Descriptor(descriptor.getDependencyIds().stream().map(RuntimeModuleId::getStringId).toList(),
        List.copyOf(descriptor.getResourcePaths())));
    }
    return result;
  }

  // Read only format metadata and validate the complete framing. Module semantics
  // and all generated bytes are read/written by the public SDK serialization API.
  private static CompactHeader compactHeader(Path path) throws IOException {
    try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
      int format = in.readInt();
      int generator = in.readInt();
      require(format == 2 && generator == 3, "Unsupported compact repository format/generator");
      require(in.readBoolean(), "Missing bootstrap metadata");
      String bootstrap = in.readUTF();
      int classpathCount = boundedCount(in.readInt());
      List<String> classpath = new ArrayList<>();
      for (int i = 0; i < classpathCount; i++) classpath.add(in.readUTF());
      require(!in.readBoolean(), "Unexpected main-plugin module metadata");
      int count = boundedCount(in.readInt());
      int unresolvedCount = boundedCount(in.readInt());
      List<String> ids = new ArrayList<>();
      for (int i = 0; i < count + unresolvedCount; i++) ids.add(in.readUTF());
      require(new TreeSet<>(ids).size() == ids.size(), "Duplicate compact module IDs");
      for (int i = 0; i < count; i++) {
        int dependencies = boundedCount(in.readInt());
        for (int j = 0; j < dependencies; j++) {
          int index = in.readInt();
          require(index >= 0 && index < ids.size(), "Invalid compact dependency index");
        }
        int resources = boundedCount(in.readInt());
        for (int j = 0; j < resources; j++) in.readUTF();
      }
      require(in.read() == -1, "Trailing compact repository bytes");
      return new CompactHeader(format, generator, bootstrap, List.copyOf(classpath), count,
        List.copyOf(ids.subList(count, count + unresolvedCount)));
    }
  }

  private static int boundedCount(int count) {
    require(count >= 0 && count <= 100000, "Invalid compact repository count");
    return count;
  }

  private static Map<String, String> options(String[] args) {
    require(args.length % 2 == 1, "Arguments must be name/value pairs");
    Map<String, String> result = new LinkedHashMap<>();
    for (int i = 1; i < args.length; i += 2) {
      require(result.put(args[i], args[i + 1]) == null, "Duplicate helper argument");
    }
    return result;
  }

  private static Path ownedRoot(Path supplied, Path sourceSdkContents) throws IOException {
    Path repository = Path.of("").toRealPath();
    require(Files.isRegularFile(repository.resolve("product/patches/GoProductModuleRepository.java"))
      && Files.isRegularFile(repository.resolve("scripts/package-product.py")), "Run the helper from the repository root");
    Path root = noSymlinks(supplied.toAbsolutePath().normalize());
    require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "Owned output root must already exist");
    require((root.startsWith(repository.resolve("build")) || root.startsWith(repository.resolve(".tools")))
      && !root.startsWith(repository.resolve(".tools/platform")), "Output root must be an owned repository build/control directory, outside the source SDK");
    require(!root.startsWith(sourceSdkContents) && !sourceSdkContents.startsWith(root), "Output ownership must not overlap the source SDK identified by the public API class origin");
    return root;
  }

  private static Path ownedPath(Path root, Path supplied, boolean mustExist) throws IOException {
    Path path = noSymlinks(supplied.toAbsolutePath().normalize());
    require(path.startsWith(root) && !path.equals(root), "Output escapes its owned root");
    require(Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS), "Output parent must already exist");
    if (mustExist) return regularFile(path);
    require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS), "Output already exists");
    return path;
  }

  private static Path regularFile(Path path) throws IOException {
    path = noSymlinks(path.toAbsolutePath().normalize());
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Expected a regular file: " + path);
    return path;
  }

  private static Path noSymlinks(Path path) {
    Path current = path.getRoot();
    for (Path component : path) {
      current = current.resolve(component);
      require(!Files.isSymbolicLink(current), "Symbolic links are not accepted: " + current);
    }
    return path;
  }

  private static String sha256(Path path) throws Exception {
    return java.util.HexFormat.of().formatHex(digest(Files.readAllBytes(path)));
  }

  private static byte[] digest(byte[] data) throws IOException {
    try { return MessageDigest.getInstance("SHA-256").digest(data); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  private static String json(Object value) {
    if (value instanceof Map<?, ?> map) {
      return "{" + String.join(",", map.entrySet().stream().map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).toList()) + "}";
    }
    if (value instanceof Collection<?> values) return "[" + String.join(",", values.stream().map(GoProductModuleRepository::json).toList()) + "]";
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    if (value == null) return "null";
    StringBuilder result = new StringBuilder("\"");
    for (char c : value.toString().toCharArray()) {
      if (c == '\\' || c == '"') result.append('\\').append(c);
      else if (c < 32) result.append(String.format("\\u%04x", (int)c));
      else result.append(c);
    }
    return result.append('"').toString();
  }
}

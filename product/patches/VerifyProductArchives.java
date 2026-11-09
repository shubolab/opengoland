/*
 * Independent read-only packaging guard using the pinned open-source IntelliJ
 * ZIP reader. Standard JDK ZIP validation alone does not validate the absolute
 * IKV offsets carried by an optimized IntelliJ archive's ZIP comment.
 */
import com.intellij.util.lang.ImmutableZipFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;

public final class VerifyProductArchives {
  public static void main(String[] args) {
    List<Map<String, Object>> verified = new ArrayList<>();
    String current = null;
    try {
      if (args.length == 0 || args.length > 32) {
        throw new IllegalArgumentException("Supply between one and 32 explicit absolute JAR paths");
      }
      for (String argument : args) {
        current = argument;
        Path path = Path.of(argument);
        if (!path.isAbsolute() || !Files.isRegularFile(path)) {
          throw new IllegalArgumentException("Archive must be an explicit absolute regular file: " + argument);
        }
        int count = 0;
        int xmlCount = 0;
        String loader;
        try (var standard = new java.util.zip.ZipFile(path.toFile());
             var platform = ImmutableZipFile.load(path)) {
          loader = platform.getClass().getSimpleName();
          var names = new HashSet<String>();
          var entries = standard.entries();
          while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!names.add(entry.getName())) {
              throw new IllegalStateException("Duplicate archive entry: " + entry.getName());
            }
            // This reserved derived index is not a normal class/resource in
            // the optimized reader. Its archive-comment activation is tested
            // by load(); all normal payload entries are read below.
            if (entry.isDirectory() || entry.getName().equals("__index__")) continue;
            var resource = platform.getResource(entry.getName());
            if (resource == null) {
              throw new IllegalStateException("Platform ZIP reader cannot find: " + entry.getName());
            }
            byte[] expected;
            try (InputStream input = standard.getInputStream(entry)) {
              expected = input.readAllBytes();
            }
            if (!Arrays.equals(expected, resource.getData())) {
              throw new IllegalStateException("Platform/JDK ZIP payload mismatch: " + entry.getName());
            }
            count++;
            if (entry.getName().endsWith(".xml")) xmlCount++;
          }
        }
        // The try-with-resources close is also required: a successful payload
        // read without a successful mapped-buffer cleanup is not a pass.
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("path", path.toString());
        record.put("bytes", Files.size(path));
        record.put("sha256", sha256(path));
        record.put("platformReader", loader);
        record.put("verifiedPayloadEntries", count);
        record.put("verifiedXmlEntries", xmlCount);
        record.put("closed", true);
        verified.add(record);
      }
      System.out.println(json(Map.of("status", "pass", "archives", verified,
        "scope", "Read-only actual IntelliJ ZIP load, every non-index payload comparison and close; not a native application startup")));
    }
    catch (Throwable failure) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("status", "fail");
      result.put("archivesVerifiedBeforeFailure", verified);
      result.put("failedArchive", current);
      result.put("errorType", failure.getClass().getName());
      result.put("message", failure.getMessage());
      System.out.println(json(result));
      System.exit(1);
    }
  }

  private static String sha256(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];
      int size;
      while ((size = input.read(buffer)) >= 0) digest.update(buffer, 0, size);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String json(Object value) {
    if (value == null) return "null";
    if (value instanceof Map<?, ?> map) {
      return "{" + String.join(",", map.entrySet().stream()
        .map(entry -> json(entry.getKey().toString()) + ":" + json(entry.getValue())).toList()) + "}";
    }
    if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(VerifyProductArchives::json).toList()) + "]";
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    StringBuilder output = new StringBuilder("\"");
    for (char character : value.toString().toCharArray()) {
      if (character == '\\' || character == '"') output.append('\\').append(character);
      else if (character < 32) output.append(String.format("\\u%04x", (int)character));
      else output.append(character);
    }
    return output.append('"').toString();
  }
}

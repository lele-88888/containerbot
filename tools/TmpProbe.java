import java.nio.file.*;
public class TmpProbe {
  public static void main(String[] a) throws Exception {
    String t = System.getProperty("java.io.tmpdir");
    System.out.println("  java.io.tmpdir = " + t);
    Path p = Paths.get(t, "__jvm_tmp_probe.txt");
    try { Files.writeString(p, "x"); Files.delete(p); System.out.println("  ✅ JVM 可写该目录"); }
    catch (Exception e) { System.out.println("  ❌ JVM 无法写入: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
  }
}

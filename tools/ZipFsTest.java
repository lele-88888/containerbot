import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 复现 Loom 写 mappings jar 时的 ReadOnlyFileSystemException。
 *
 * 背景：jdk.nio.zipfs 的 ZipFileSystem 构造里有这么一句
 *     this.readOnly = ro || !Files.isWritable(zfpath);
 * 也就是说，只要 Files.isWritable(目标 jar) 为 false，随后任何写操作都会抛
 * ReadOnlyFileSystemException（消息为 null）——正好对应 Loom 的报错。
 *
 * 用法: java ZipFsTest <目录>
 */
public class ZipFsTest {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]).toAbsolutePath();
        System.out.println("测试目录: " + dir);
        System.out.println("  目录存在   = " + Files.exists(dir));
        System.out.println("  目录 isWritable = " + Files.isWritable(dir));

        Files.createDirectories(dir);

        // ---- 用例 1：全新建一个 zip 再写入 ----
        Path jar = dir.resolve("zipfs-probe.jar");
        Files.deleteIfExists(jar);
        try (FileSystem fs = FileSystems.newFileSystem(jar, Map.of("create", "true"))) {
            Files.writeString(fs.getPath("/a.txt"), "hello");
        }
        System.out.println("\n[1] 新建 zipfs 并写入: 成功");
        System.out.println("    jar 大小        = " + Files.size(jar));
        System.out.println("    isWritable(jar) = " + Files.isWritable(jar));
        System.out.println("    File.canWrite() = " + jar.toFile().canWrite());

        // ---- 用例 2：重新打开已存在的 zip 追加写入（Loom 走的正是这条路）----
        try (FileSystem fs = FileSystems.newFileSystem(jar, Map.of())) {
            Files.writeString(fs.getPath("/b.txt"), "world");
            System.out.println("\n[2] 重新打开并追加写入: 成功");
        } catch (Exception e) {
            System.out.println("\n[2] 重新打开并追加写入: 失败");
            System.out.println("    异常 = " + e.getClass().getName());
            System.out.println("    消息 = " + e.getMessage());
        }

        // ---- 用例 3：显式不设 create 打开一个不存在的 zip ----
        Path missing = dir.resolve("zipfs-missing.jar");
        Files.deleteIfExists(missing);
        try (FileSystem fs = FileSystems.newFileSystem(missing, Map.of())) {
            System.out.println("\n[3] 打开不存在的 zip: 竟然成功");
        } catch (Exception e) {
            System.out.println("\n[3] 打开不存在的 zip: 失败 -> " + e.getClass().getSimpleName());
        }

        System.out.println("\n结论：若 [2] 失败，说明该目录下已存在的 jar 对 JVM 而言不可写，");
        System.out.println("      Loom 的 mappings 写入就会稳定报 ReadOnlyFileSystemException。");
    }
}

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributes;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 精确判定「Java 到底能不能写」——区分普通文件写入、ZipOutputStream 写入、
 * 以及 jdk.nio.zipfs 写入这三条路径，并打印 Files.isWritable 与 File.canWrite 的差异。
 *
 * 用法: java WriteProbe <目录>
 */
public class WriteProbe {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(dir);

        System.out.println("目录: " + dir);
        System.out.println("  存在            = " + Files.exists(dir));
        System.out.println("  Files.isWritable= " + Files.isWritable(dir));
        System.out.println("  File.canWrite   = " + dir.toFile().canWrite());
        System.out.println();

        // ---------- 1. 普通文件写入 ----------
        Path plain = dir.resolve("plain.txt");
        try {
            Files.writeString(plain, "hello");
            System.out.println("[1] 普通文件写入: 成功, 大小=" + Files.size(plain));
            System.out.println("    isWritable=" + Files.isWritable(plain)
                    + "  canWrite=" + plain.toFile().canWrite());
        } catch (Exception e) {
            System.out.println("[1] 普通文件写入: 失败 -> " + e);
        }

        // ---------- 2. ZipOutputStream 写入（不走 zipfs） ----------
        Path zos = dir.resolve("by-stream.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zos.toFile()))) {
            z.putNextEntry(new ZipEntry("a.txt"));
            z.write("hi".getBytes());
            z.closeEntry();
        }
        System.out.println("\n[2] ZipOutputStream 写入: 成功, 大小=" + Files.size(zos)
                + ", isWritable=" + Files.isWritable(zos));

        // ---------- 3. zipfs 新建并写入 ----------
        Path zf = dir.resolve("by-zipfs.jar");
        Files.deleteIfExists(zf);
        try (FileSystem fs = FileSystems.newFileSystem(zf, Map.of("create", "true"))) {
            Files.writeString(fs.getPath("/a.txt"), "hi");
            System.out.println("\n[3] zipfs 写入: 成功");
        } catch (Exception e) {
            System.out.println("\n[3] zipfs 写入: 失败 -> " + e.getClass().getName());
            System.out.println("    此时 zf 是否存在 = " + Files.exists(zf));
            if (Files.exists(zf)) {
                System.out.println("    isWritable(zf)   = " + Files.isWritable(zf));
                System.out.println("    canWrite(zf)     = " + zf.toFile().canWrite());
            }
        }

        // ---------- 4. DOS 属性 ----------
        System.out.println("\n[4] DOS 属性对比:");
        for (Path p : new Path[]{plain, zos, zf}) {
            if (!Files.exists(p)) { System.out.println("    " + p.getFileName() + " 不存在"); continue; }
            try {
                DosFileAttributes a = Files.readAttributes(p, DosFileAttributes.class);
                System.out.printf("    %-16s readOnly=%s hidden=%s archive=%s system=%s%n",
                        p.getFileName(), a.isReadOnly(), a.isHidden(), a.isArchive(), a.isSystem());
            } catch (Exception e) {
                System.out.println("    " + p.getFileName() + " 读取属性失败: " + e);
            }
        }

        // ---------- 5. 运行身份 ----------
        System.out.println("\n[5] 运行环境:");
        System.out.println("    user.name    = " + System.getProperty("user.name"));
        System.out.println("    os.name      = " + System.getProperty("os.name"));
        System.out.println("    java.version = " + System.getProperty("java.version"));
        System.out.println("    java.io.tmpdir = " + System.getProperty("java.io.tmpdir"));
    }
}

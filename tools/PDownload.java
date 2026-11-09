import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 多连接分段下载器（断点续传 + 自动重试）。
 *
 * <p>为什么要自己写：本机 Windows Schannel 被文件沙盒封锁，curl / BITS / WinHTTP
 * 的 HTTPS 全部不可用，Java 的 JSSE 是唯一能走通的通道；而单线程下载在国内镜像上
 * 会被限速到 100~300 KB/s。这里改用 N 条并发 Range 连接抢带宽。</p>
 *
 * <p>断点续传策略：若已存在 {@code <目标>.part}，把它当作 [0, len) 的前缀（这适用于
 * 之前线性下载留下的残留）；剩余区间再切分成多个段，每段各自写入
 * {@code <目标>.part.segN} 并独立续传。全部完成后按序拼接成最终文件。
 * 这样任何时刻中断，重跑都能从断点继续。</p>
 *
 * <p>用法: java PDownload &lt;目标文件&gt; &lt;URL&gt; [连接数]</p>
 */
public class PDownload {

    private static String url;
    private static long total;
    private static volatile long done;      // 各段已完成字节合计（含前缀）
    private static volatile boolean stop;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: java PDownload <目标文件> <URL> [连接数]");
            System.exit(2);
        }

        Path dest = Path.of(args[0]).toAbsolutePath();
        url = args[1];
        int conns = args.length > 2 ? Integer.parseInt(args[2]) : 6;

        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        Path part = dest.resolveSibling(dest.getFileName() + ".part");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        total = probeTotal(client);
        System.out.printf("来源: %s%n总大小: %.2f MB (%d 字节)%n", url, total / 1048576.0, total);

        long prefixLen = Files.exists(part) ? Files.size(part) : 0;
        if (prefixLen > total) {
            System.out.println("  已有分片比总长还大，视为损坏，清除后重下");
            Files.deleteIfExists(part);
            prefixLen = 0;
        }
        System.out.printf("已有前缀: %.2f MB%n", prefixLen / 1048576.0);

        long remaining = total - prefixLen;
        if (remaining <= 0) {
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
            System.out.println(">>> 已完整，直接完成: " + dest);
            return;
        }

        // 把剩余区间切成 conns 段
        long segLen = Math.max(1, remaining / conns);
        List<Seg> segs = new ArrayList<>();
        for (int i = 0; i < conns; i++) {
            long s = prefixLen + i * segLen;
            if (s >= total) break;
            long e = (i == conns - 1) ? total - 1 : Math.min(total - 1, s + segLen - 1);
            Path f = dest.resolveSibling(dest.getFileName() + ".part.seg" + i);
            segs.add(new Seg(i, s, e, f));
        }

        done = prefixLen;
        for (Seg s : segs) {
            done += Files.exists(s.file) ? Math.min(Files.size(s.file), s.length()) : 0;
        }
        System.out.printf("分 %d 段并发下载，剩余 %.2f MB%n", segs.size(), remaining / 1048576.0);

        Thread monitor = new Thread(() -> {
            long last = -1;
            while (!stop) {
                try {
                    Thread.sleep(4000);
                    long now = done;
                    if (now != last) {
                        System.out.printf("    进度 %.1f / %.1f MB  (%.1f%%)%n",
                                now / 1048576.0, total / 1048576.0, now * 100.0 / total);
                        last = now;
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        monitor.setDaemon(true);
        monitor.start();

        ExecutorService pool = Executors.newFixedThreadPool(segs.size());
        List<Future<?>> futures = new ArrayList<>();
        for (Seg s : segs) {
            futures.add(pool.submit(() -> runSegment(client, s)));
        }

        boolean ok = true;
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                ok = false;
                Throwable r = e.getCause() != null ? e.getCause() : e;
                System.out.println("  某段失败: " + r.getMessage());
            }
        }
        pool.shutdownNow();
        stop = true;

        if (!ok) {
            System.err.println("存在未完成的段，请重新运行本命令续传（已完成部分不会丢失）");
            System.exit(1);
        }

        // 校验每段长度
        for (Seg s : segs) {
            long actual = Files.size(s.file);
            if (actual != s.length()) {
                System.err.printf("段 %d 长度不符: %d != %d，请重跑%n", s.index, actual, s.length());
                System.exit(1);
            }
        }

        // 拼接：前缀 + 各段
        System.out.println("拼接中...");
        Path assembled = dest.resolveSibling(dest.getFileName() + ".assembled");
        try (OutputStream out = Files.newOutputStream(assembled,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            if (prefixLen > 0) {
                copyFile(part, out);
            }
            for (Seg s : segs) {
                copyFile(s.file, out);
            }
        }

        long finalLen = Files.size(assembled);
        if (finalLen != total) {
            System.err.printf("拼接结果长度不符: %d != %d%n", finalLen, total);
            System.exit(1);
        }

        Files.move(assembled, dest, StandardCopyOption.REPLACE_EXISTING);

        // 清理中间文件
        Files.deleteIfExists(part);
        for (Seg s : segs) {
            Files.deleteIfExists(s.file);
        }

        System.out.printf(">>> 完成: %s  (%.2f MB)%n", dest, finalLen / 1048576.0);
    }

    // ------------------------------------------------------------------ 单段下载

    private static void runSegment(HttpClient client, Seg seg) {
        // 该方法作为 Runnable 提交给线程池，不能抛受检异常，故在此转成非受检异常
        long already;
        try {
            already = Files.exists(seg.file) ? Files.size(seg.file) : 0;
        } catch (IOException e) {
            throw new RuntimeException("无法读取段文件大小: " + seg.file, e);
        }

        long pos = seg.start + already;
        if (pos > seg.end) {
            return; // 该段已完成
        }

        int attempt = 0;
        while (pos <= seg.end) {
            if (++attempt > 80) {
                throw new RuntimeException("段 " + seg.index + " 重试耗尽（" + pos + "/" + seg.end + "）");
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(10))
                        .header("Range", "bytes=" + pos + "-" + seg.end)
                        .GET()
                        .build();

                HttpResponse<InputStream> resp =
                        client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int code = resp.statusCode();
                if (code != 200 && code != 206) {
                    resp.body().close();
                    throw new IOException("HTTP " + code);
                }

                try (InputStream in = resp.body();
                     OutputStream out = Files.newOutputStream(seg.file,
                             StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                    // 服务器若忽略 Range 返回 200 全量，则先跳过已下载部分
                    if (code == 200) {
                        long skip = pos - seg.start;
                        long skipped = 0;
                        while (skipped < skip) {
                            long s = in.skip(skip - skipped);
                            if (s <= 0) break;
                            skipped += s;
                        }
                    }

                    byte[] buf = new byte[128 * 1024];
                    int n;
                    while (pos <= seg.end && (n = in.read(buf)) > 0) {
                        int write = (int) Math.min(n, seg.end - pos + 1);
                        out.write(buf, 0, write);
                        pos += write;
                        done += write;
                    }
                }
            } catch (Exception e) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private static long probeTotal(HttpClient client) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .header("Range", "bytes=0-0")
                .GET()
                .build();
        HttpResponse<InputStream> resp =
                client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        resp.body().close();

        if (resp.statusCode() == 206) {
            String cr = resp.headers().firstValue("content-range").orElse(null);
            if (cr != null) {
                int slash = cr.lastIndexOf('/');
                if (slash > 0) {
                    return Long.parseLong(cr.substring(slash + 1).trim());
                }
            }
        }
        long len = resp.headers().firstValueAsLong("content-length").orElse(-1);
        if (len <= 0) {
            throw new IOException("无法确定文件总大小（HTTP " + resp.statusCode() + "）");
        }
        return len;
    }

    private static void copyFile(Path src, OutputStream out) throws IOException {
        try (InputStream in = Files.newInputStream(src)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private record Seg(int index, long start, long end, Path file) {
        long length() {
            return end - start + 1;
        }
    }
}

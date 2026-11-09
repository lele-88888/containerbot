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

/**
 * 支持断点续传与自动重试的下载器。
 *
 * <p>动机：国内镜像常在传输中途重置连接（已实测 TUNA 在 80MB 处 reset）。
 * 这里用 HTTP Range 从已下载的字节数继续，并在中断后自动重试若干次，
 * 避免每次失败都从头再来。</p>
 *
 * <p>用法: java Resume &lt;目标文件&gt; &lt;URL&gt; [最大重试次数]</p>
 */
public class Resume {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: java Resume <目标文件> <URL> [最大重试次数]");
            System.exit(2);
        }

        Path dest = Path.of(args[0]).toAbsolutePath();
        String url = args[1];
        int maxAttempts = args.length > 2 ? Integer.parseInt(args[2]) : 40;

        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        System.out.println("目标: " + dest);
        System.out.println("来源: " + url);

        long knownTotal = -1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long have = Files.exists(tmp) ? Files.size(tmp) : 0;

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(30))
                    .GET();
            if (have > 0) {
                builder.header("Range", "bytes=" + have + "-");
            }

            try {
                HttpResponse<InputStream> resp =
                        client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                int code = resp.statusCode();

                if (code != 200 && code != 206) {
                    resp.body().close();
                    throw new IOException("HTTP " + code);
                }
                if (have > 0 && code == 200) {
                    // 服务器忽略了 Range：只能从头下
                    System.out.println("  服务器不支持断点续传，重新开始");
                    have = 0;
                    resp.body().close();
                    Files.deleteIfExists(tmp);
                    continue;
                }

                long len = resp.headers().firstValueAsLong("content-length").orElse(-1);
                if (len > 0) {
                    knownTotal = len + have;
                }

                System.out.printf("[第 %d 次尝试] 已有 %.1f MB / 共 %.1f MB%n",
                        attempt, have / 1048576.0,
                        knownTotal < 0 ? -1 : knownTotal / 1048576.0);

                long written = have;
                long lastReport = 0;
                try (InputStream in = resp.body();
                     OutputStream out = Files.newOutputStream(tmp,
                             StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    byte[] buf = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        written += n;
                        long now = System.currentTimeMillis();
                        if (now - lastReport > 5000) {
                            System.out.printf("    %.1f MB%n", written / 1048576.0);
                            lastReport = now;
                        }
                    }
                }

                long actual = Files.size(tmp);
                if (knownTotal > 0 && actual != knownTotal) {
                    throw new IOException("大小不符: 实际 " + actual + "，期望 " + knownTotal);
                }

                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                System.out.printf(">>> 完成: %s  (%.1f MB)%n", dest, Files.size(dest) / 1048576.0);
                return;

            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                System.out.printf("    中断(%s: %s)，5 秒后续传%n",
                        root.getClass().getSimpleName(), root.getMessage());
                Thread.sleep(5000);
            }
        }

        System.err.println("重试次数耗尽，仍然失败");
        System.exit(1);
    }
}

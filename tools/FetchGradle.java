import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * 带镜像回退的 Gradle 下载器。
 *
 * <p>背景：本机 Windows Schannel 被文件沙盒封锁，PowerShell/curl 的 HTTPS 全废，
 * 只有 Java 的 JSSE 能联网；而 services.gradle.org 真正的压缩包会重定向到
 * 一个连接超时的 CDN 主机。因此这里按顺序尝试多个镜像，取第一个能下的。</p>
 *
 * <p>用法: java FetchGradle <目标目录></p>
 */
public class FetchGradle {

    private static final String[] MIRRORS = {
        "https://mirrors.huaweicloud.com/gradle/",
        "https://mirrors.cloud.tencent.com/gradle/",
        "https://mirrors.aliyun.com/gradle/",
        "https://mirrors.ustc.edu.cn/gradle/",
        "https://mirrors.tuna.tsinghua.edu.cn/gradle/",
        "https://downloads.gradle.org/distributions/",
        "https://services.gradle.org/distributions/",
    };

    /** 版本优先级 × 发行包类型 */
    private static final String[] FILES = {
        "gradle-9.8.0-all.zip",
        "gradle-9.8.0-bin.zip",
        "gradle-9.7.1-all.zip",
        "gradle-9.7.1-bin.zip",
    };

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath();
        Files.createDirectories(dir);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (String file : FILES) {
            for (String mirror : MIRRORS) {
                String url = mirror + file;
                Path dest = dir.resolve(file);
                Path tmp = dir.resolve(file + ".part");

                System.out.println("--- 尝试 " + url);
                long start = System.currentTimeMillis();
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofMinutes(10))
                            .GET().build();
                    HttpResponse<InputStream> resp =
                            client.send(req, HttpResponse.BodyHandlers.ofInputStream());

                    if (resp.statusCode() / 100 != 2) {
                        resp.body().close();
                        System.out.println("    跳过: HTTP " + resp.statusCode());
                        continue;
                    }

                    long declared = resp.headers().firstValueAsLong("content-length").orElse(-1);
                    System.out.printf("    HTTP 200, 大小 %.1f MB，开始下载%n",
                            declared < 0 ? -1 : declared / 1024.0 / 1024.0);

                    long total = 0;
                    long lastReport = 0;
                    try (InputStream in = resp.body();
                         OutputStream out = Files.newOutputStream(tmp)) {
                        byte[] buf = new byte[128 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            total += n;
                            long now = System.currentTimeMillis();
                            if (now - lastReport > 2000) {
                                System.out.printf("      %.1f MB  (%.0f KB/s)%n",
                                        total / 1024.0 / 1024.0,
                                        total / 1024.0 / Math.max(1, (now - start) / 1000.0));
                                lastReport = now;
                            }
                        }
                    }

                    // 校验大小，避免下到半截的 HTML 错误页
                    if (declared > 0 && total != declared) {
                        System.out.printf("    失败: 大小不符 (期望 %d, 实际 %d)%n", declared, total);
                        Files.deleteIfExists(tmp);
                        continue;
                    }

                    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                    System.out.printf(">>> 成功: %s  (%.1f MB, 来自 %s)%n",
                            dest, total / 1024.0 / 1024.0, mirror);
                    System.out.println("RESULT_PATH=" + dest);
                    return;
                } catch (Exception e) {
                    Throwable root = e;
                    while (root.getCause() != null) root = root.getCause();
                    System.out.printf("    失败(%dms): %s%n",
                            System.currentTimeMillis() - start, root.getClass().getSimpleName());
                    try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
                }
            }
        }

        System.err.println("所有镜像均失败");
        System.exit(1);
    }
}

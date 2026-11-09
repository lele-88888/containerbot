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
 * 极简 Java 下载器。
 *
 * 存在的理由：本机 Windows Schannel 被文件沙盒封锁（无法访问 Crypto 密钥库），
 * 导致 PowerShell / curl 的一切 HTTPS 请求失败，而 Java 自带的 JSSE 不受影响。
 * 因此所有需要联网下载的工作都必须经由 Java 完成。
 *
 * 用法: java Download.java <目标文件路径> <URL>
 */
public class Download {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("用法: java Download.java <目标文件路径> <URL>");
            System.exit(2);
        }

        Path dest = Path.of(args[0]).toAbsolutePath();
        String url = args[1];

        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }

        System.out.println("下载: " + url);
        System.out.println("目标: " + dest);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build();

        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        System.out.println("HTTP " + response.statusCode()
                + "  Content-Length: " + response.headers().firstValueAsLong("content-length").orElse(-1));

        if (response.statusCode() / 100 != 2) {
            System.err.println("下载失败：非 2xx 状态码 " + response.statusCode());
            System.exit(1);
        }

        // 先写临时文件，成功后再原子替换，避免半途失败留下损坏文件
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        long total = 0;
        long lastReport = 0;

        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                long now = System.currentTimeMillis();
                if (now - lastReport > 1000) {
                    System.out.printf("  已下载 %.2f MB%n", total / 1024.0 / 1024.0);
                    lastReport = now;
                }
            }
        }

        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("完成: %s  (%.2f MB)%n", dest, total / 1024.0 / 1024.0);
    }
}

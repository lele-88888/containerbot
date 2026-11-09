import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 多镜像 x 多版本 的可用性 + 速度探测。
 * 用法: java Probe <文件名1> [文件名2] ...
 * 例:   java Probe gradle-9.7.1-bin.zip gradle-9.8.0-bin.zip
 */
public class Probe {

    private static final String[] BASES = {
        "https://services.gradle.org/distributions/",
        "https://downloads.gradle.org/distributions/",
        "https://mirrors.cloud.tencent.com/gradle/",
        "https://mirrors.huaweicloud.com/gradle/",
        "https://mirrors.aliyun.com/gradle/",
        "https://mirrors.tuna.tsinghua.edu.cn/gradle/",
        "https://mirrors.ustc.edu.cn/gradle/",
    };

    public static void main(String[] args) {
        if (args.length == 0) {
            args = new String[]{"gradle-9.7.1-bin.zip"};
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (String file : args) {
            System.out.println("### " + file);
            for (String base : BASES) {
                String url = base + file;
                long start = System.currentTimeMillis();
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(20))
                            .GET().build();
                    HttpResponse<InputStream> resp =
                            client.send(req, HttpResponse.BodyHandlers.ofInputStream());

                    long ttf = System.currentTimeMillis() - start;
                    long declared = resp.headers().firstValueAsLong("content-length").orElse(-1);

                    if (resp.statusCode() / 100 != 2) {
                        resp.body().close();
                        System.out.printf("  %-46s HTTP %d (无此文件)%n", shortName(base), resp.statusCode());
                        continue;
                    }

                    long read = 0;
                    long readStart = System.currentTimeMillis();
                    try (InputStream in = resp.body()) {
                        byte[] buf = new byte[16 * 1024];
                        int n;
                        while (read < 256 * 1024 && (n = in.read(buf)) > 0) {
                            read += n;
                        }
                    }
                    long readMs = Math.max(1, System.currentTimeMillis() - readStart);
                    double kbps = read / 1024.0 / (readMs / 1000.0);

                    System.out.printf("  %-46s HTTP %d  首字节 %5dms  %8.1f KB/s  总大小 %.1f MB%n",
                            shortName(base), resp.statusCode(), ttf, kbps,
                            declared < 0 ? -1 : declared / 1024.0 / 1024.0);
                } catch (Exception e) {
                    long ms = System.currentTimeMillis() - start;
                    Throwable root = e;
                    while (root.getCause() != null) root = root.getCause();
                    System.out.printf("  %-46s 失败(%dms): %s%n",
                            shortName(base), ms, root.getClass().getSimpleName());
                }
            }
            System.out.println();
        }
    }

    private static String shortName(String base) {
        return base.replace("https://", "")
                   .replace("/gradle/", "")
                   .replace("/distributions/", "");
    }
}

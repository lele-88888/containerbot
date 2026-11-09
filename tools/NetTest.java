import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 用于判定「本机能否编译 Fabric 模组」的连通性探针。
 * 关键点：Java 使用自带的 JSSE + cacerts，不依赖被沙盒封锁的 Windows Schannel 密钥库。
 */
public class NetTest {

    private static final String[] TARGETS = {
        "https://www.baidu.com/",                         // 基线：国内站点
        "https://maven.fabricmc.net/",                    // Fabric 官方 Maven
        "https://repo1.maven.org/maven2/",                // Maven Central
        "https://services.gradle.org/distributions/",      // Gradle 发行版
        "https://libraries.minecraft.net/",               // Minecraft 依赖库
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", // Mojang 元数据
        "https://maven.aliyun.com/repository/public/",     // 国内镜像：阿里云
        "https://bmclapi2.bangbang93.com/maven/",          // 国内镜像：BMCLAPI
        "https://mirrors.cloud.tencent.com/gradle/",       // 国内镜像：腾讯云 Gradle
    };

    public static void main(String[] args) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        System.out.println("Java: " + System.getProperty("java.version")
                + "   vendor: " + System.getProperty("java.vendor"));
        System.out.println("TLS 默认实现: " + System.getProperty("https.protocols", "(default)"));
        System.out.println("--------------------------------------------------------------");

        int ok = 0;
        for (String url : TARGETS) {
            String result;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(25))
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                result = "OK   HTTP " + resp.statusCode() + "  (body " + resp.body().length() + " bytes)";
                ok++;
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                result = "FAIL " + root.getClass().getSimpleName() + ": " + root.getMessage();
            }
            System.out.printf("  %-62s %s%n", shorten(url), result);
        }

        System.out.println("--------------------------------------------------------------");
        System.out.println("可达数量: " + ok + " / " + TARGETS.length);
        System.out.println(ok > 0
                ? ">>> Java 网络可用，Gradle 具备拉取依赖的条件"
                : ">>> Java 网络也不可用，需要让用户提供离线依赖或代理");
    }

    private static String shorten(String url) {
        return url.length() <= 60 ? url : url.substring(0, 57) + "...";
    }
}

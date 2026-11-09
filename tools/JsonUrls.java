import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抓取一个 JSON 文本，把其中出现的所有 URL 去重打印。
 * 用于确认 BMCLAPI 是否把 Mojang 的下载地址重写到了自己身上。
 *
 * 用法: java JsonUrls <URL>
 */
public class JsonUrls {

    /** 只关心我们真正需要的几类地址 */
    private static final Pattern P = Pattern.compile("https?://[A-Za-z0-9\\.\\-/_%\\+]{6,160}");

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (String url : args) {
            System.out.println("### " + url);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                System.out.println("HTTP " + resp.statusCode() + ", body " + resp.body().length() + " 字节");

                Set<String> hosts = new LinkedHashSet<>();
                Matcher m = P.matcher(resp.body());
                while (m.find()) {
                    String u = m.group();
                    // 归类到 host + 前两段路径，便于观察整体指向
                    try {
                        URI x = URI.create(u);
                        String path = x.getPath() == null ? "" : x.getPath();
                        String[] seg = path.split("/");
                        String prefix = seg.length > 2 ? "/" + seg[1] + "/" + seg[2] : path;
                        hosts.add(x.getHost() + prefix);
                    } catch (Exception ignored) {
                    }
                }
                hosts.stream().limit(40).forEach(h -> System.out.println("   " + h));
            } catch (Exception e) {
                Throwable r = e;
                while (r.getCause() != null) r = r.getCause();
                System.out.println("失败: " + r.getClass().getSimpleName() + ": " + r.getMessage());
            }
            System.out.println();
        }
    }
}

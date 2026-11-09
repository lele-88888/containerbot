import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 通用 URL 可用性检查器。
 * 用法: java Check <URL1> [URL2] ...
 */
public class Check {

    public static void main(String[] args) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (String url : args) {
            long t0 = System.currentTimeMillis();
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .GET().build();
                HttpResponse<String> resp =
                        client.send(req, HttpResponse.BodyHandlers.ofString());
                long ms = System.currentTimeMillis() - t0;
                System.out.printf("  HTTP %-3d  %5dms  %s%n", resp.statusCode(), ms, url);
            } catch (Exception e) {
                long ms = System.currentTimeMillis() - t0;
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                System.out.printf("  失败      %5dms  %s%n      -> %s%n",
                        ms, url, root.getClass().getSimpleName() + ": " + root.getMessage());
            }
        }
    }
}

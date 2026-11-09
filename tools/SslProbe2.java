import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.net.URL;

/**
 * 精确复现 Fabric 安装器的 SSL 调用路径。
 *
 * 安装器走的是 HttpsURLConnection —— 它内部调用
 *     HttpsURLConnection.getDefaultSSLSocketFactory()
 *       -> SSLSocketFactory.getDefault()
 *         -> SSLContext.getDefault()
 * 而直接调用 SSLContext.getDefault() 在本机是成功的。
 * 本探针逐层调用，定位到底哪一层开始失败。
 */
public class SslProbe2 {

    public static void main(String[] args) {
        step("1. SSLContext.getDefault()", () -> SSLContext.getDefault().getProtocol());
        step("2. SSLSocketFactory.getDefault()", () -> SSLSocketFactory.getDefault().toString());
        step("3. HttpsURLConnection.getDefaultSSLSocketFactory()",
                () -> HttpsURLConnection.getDefaultSSLSocketFactory().toString());
        step("4. new URL(https).openConnection()",
                () -> new URL("https://meta.fabricmc.net/v2/versions/loader").openConnection().toString());
        step("5. 真正发起连接并读取", () -> {
            HttpsURLConnection c = (HttpsURLConnection)
                    new URL("https://meta.fabricmc.net/v2/versions/loader").openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            return "HTTP " + c.getResponseCode();
        });
    }

    private interface Step {
        String run() throws Exception;
    }

    private static void step(String label, Step s) {
        try {
            System.out.println("[OK]   " + label + "  -> " + s.run());
        } catch (Throwable t) {
            System.out.println("[FAIL] " + label);
            Throwable r = t;
            int d = 0;
            while (r != null && d < 5) {
                System.out.println("         " + r.getClass().getName() + ": " + r.getMessage());
                r = r.getCause();
                d++;
            }
        }
    }
}

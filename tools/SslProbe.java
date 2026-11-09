import javax.net.ssl.SSLContext;

/**
 * 验证「默认 SSL 上下文能否初始化」。
 *
 * 本机现象：JDK 初始化默认 SSLContext 时抛
 *   java.security.KeyManagementException: problem accessing trust store
 * 怀疑根因与 Files.isWritable() 恒为 false 相同——JDK 认为 cacerts 不可写，
 * 于是走「做一份可写副本」的回退分支，而该分支在本机环境下失败。
 *
 * 用法:
 *   java SslProbe                      # 用默认 trustStore
 *   java -Djavax.net.ssl.trustStore=<路径> SslProbe    # 指定 trustStore
 */
public class SslProbe {

    public static void main(String[] args) {
        System.out.println("java.version            = " + System.getProperty("java.version"));
        System.out.println("java.home               = " + System.getProperty("java.home"));
        System.out.println("javax.net.ssl.trustStore= " + System.getProperty("javax.net.ssl.trustStore"));

        // 1. 默认 SSLContext
        try {
            SSLContext ctx = SSLContext.getDefault();
            System.out.println("[1] SSLContext.getDefault() -> OK, protocol=" + ctx.getProtocol());
        } catch (Throwable t) {
            System.out.println("[1] SSLContext.getDefault() -> 失败");
            Throwable r = t;
            int depth = 0;
            while (r != null && depth < 6) {
                System.out.println("      " + r.getClass().getName() + ": " + r.getMessage());
                r = r.getCause();
                depth++;
            }
        }

        // 2. 直接读 cacerts，看看文件本身能否访问
        String home = System.getProperty("java.home");
        java.nio.file.Path cacerts = java.nio.file.Path.of(home, "lib", "security", "cacerts");
        System.out.println();
        System.out.println("[2] cacerts 文件状态");
        System.out.println("      path           = " + cacerts);
        System.out.println("      exists         = " + java.nio.file.Files.exists(cacerts));
        System.out.println("      Files.isReadable= " + java.nio.file.Files.isReadable(cacerts));
        System.out.println("      Files.isWritable= " + java.nio.file.Files.isWritable(cacerts));
        System.out.println("      File.canRead    = " + cacerts.toFile().canRead());
        System.out.println("      File.canWrite   = " + cacerts.toFile().canWrite());
        try {
            System.out.println("      size            = " + java.nio.file.Files.size(cacerts));
        } catch (Exception e) {
            System.out.println("      size            = 读取失败: " + e);
        }

        // 3. 能否真正作为 KeyStore 加载
        System.out.println();
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(cacerts)) {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
            ks.load(in, "changeit".toCharArray());
            System.out.println("[3] 直接加载 cacerts -> OK, 条目数=" + ks.size());
        } catch (Throwable t) {
            System.out.println("[3] 直接加载 cacerts -> 失败: " + t);
        }
    }
}

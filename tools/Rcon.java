import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Minecraft RCON 客户端。
 *
 * <p>支持两种传入命令的方式：</p>
 * <pre>
 *   java Rcon &lt;host&gt; &lt;port&gt; &lt;password&gt; &lt;命令1&gt; [命令2 ...]
 *   java Rcon &lt;host&gt; &lt;port&gt; &lt;password&gt; --file &lt;命令文件&gt;
 * </pre>
 *
 * <p><b>为什么需要 --file</b>：PowerShell 5.1 在向原生程序传参时会吞掉参数里的双引号，
 * 导致诸如</p>
 * <pre>
 *   item replace block 5 -60 5 container.0 with minecraft:shulker_box[minecraft:container=[{slot:0b,item:{id:"minecraft:diamond",count:64}}]]
 * </pre>
 * <p>发出去时引号消失、Minecraft 解析失败。改成从 UTF-8 文件逐行读取即可完全绕开
 * PowerShell 的引号处理。以 # 开头的行为注释。</p>
 */
public class Rcon implements AutoCloseable {

    private static final int TYPE_COMMAND = 2;
    private static final int TYPE_LOGIN = 3;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;

    public Rcon(String host, int port, String password) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10000);
        socket.setSoTimeout(20000);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        send(TYPE_LOGIN, password);
        Packet p = receive();
        if (p.id == -1) {
            throw new IOException("RCON 认证失败（密码错误？）");
        }
    }

    public String command(String cmd) throws IOException {
        send(TYPE_COMMAND, cmd);
        return receive().body;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    // ------------------------------------------------------------------

    private void send(int type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        int id = nextId++;
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 4 + b.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(4 + 4 + b.length + 2);   // 剩余长度
        buf.putInt(id);
        buf.putInt(type);
        buf.put(b);
        buf.put((byte) 0);
        buf.put((byte) 0);
        out.write(buf.array());
        out.flush();
    }

    private Packet receive() throws IOException {
        int len = Integer.reverseBytes(in.readInt());
        if (len < 8 || len > 4 * 1024 * 1024) {
            throw new IOException("RCON 包长度异常: " + len);
        }
        byte[] data = new byte[len];
        in.readFully(data);

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int id = buf.getInt();
        int type = buf.getInt();
        byte[] rest = new byte[len - 8];
        buf.get(rest);

        int end = rest.length;
        while (end > 0 && rest[end - 1] == 0) {
            end--;
        }
        return new Packet(id, type, new String(rest, 0, end, StandardCharsets.UTF_8));
    }

    private record Packet(int id, int type, String body) {
    }

    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("用法:");
            System.err.println("  java Rcon <host> <port> <password> <命令1> [命令2 ...]");
            System.err.println("  java Rcon <host> <port> <password> --file <命令文件>");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String password = args[2];

        List<String> cmds = new ArrayList<>();
        if ("--file".equals(args[3])) {
            if (args.length < 5) {
                System.err.println("--file 需要指定文件路径");
                System.exit(2);
            }
            for (String line : Files.readAllLines(Path.of(args[4]), StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#")) {
                    cmds.add(s);
                }
            }
        } else {
            for (int i = 3; i < args.length; i++) {
                cmds.add(args[i]);
            }
        }

        try (Rcon rcon = new Rcon(host, port, password)) {
            for (String cmd : cmds) {
                String resp;
                try {
                    resp = rcon.command(cmd);
                } catch (IOException e) {
                    System.out.println(">> " + cmd);
                    System.out.println("   [RCON 错误] " + e.getMessage());
                    continue;
                }
                System.out.println(">> " + cmd);
                if (resp != null && !resp.isEmpty()) {
                    for (String line : resp.split("\n")) {
                        System.out.println("   " + line);
                    }
                }
            }
        }
    }
}

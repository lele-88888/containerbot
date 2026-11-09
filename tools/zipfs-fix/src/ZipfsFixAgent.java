import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * 修复本机的 JDK 可写性判定缺陷。
 *
 * <h3>问题</h3>
 * 本机（Windows 11 26200）上 {@code Files.isWritable()} 对任何路径都返回 false，
 * 而实际写入完全正常：
 * <pre>
 *   Files.isWritable = false      &lt;- 说谎
 *   File.canWrite    = true
 *   DOS readOnly     = false
 *   普通文件写入      -&gt; 成功
 *   ZipOutputStream  -&gt; 成功
 * </pre>
 *
 * <h3>根因</h3>
 * JDK 在 Windows 上通过 {@code AbstractFileSystemProvider.isWritable} 判定，
 * 而它把异常吞掉后返回 false：
 * <pre>
 *   public boolean isWritable(Path obj) {
 *       try { checkAccess(obj, AccessMode.WRITE); return true; }
 *       catch (IOException e) { return false; }     // &lt;- 异常被吞
 *   }
 * </pre>
 * {@code WindowsFileSystemProvider.checkAccess(WRITE)} 抛异常，是因为它底层用
 * {@code GetEffectiveRightsFromAcl} 计算 ACL 有效权限，而该 Win32 函数不识别
 * 登录会话关联的组权限（如 Authenticated Users）与调用者特权。
 *
 * <h3>受害组件（都栽在同一个 API 上）</h3>
 * <ul>
 *   <li>{@code jdk.nio.zipfs.ZipFileSystem} —— {@code readOnly = ... || !Files.isWritable(zfpath)}，
 *       每个 zip/jar 被强制只读；Fabric Loom 写 mappings 直接崩溃</li>
 *   <li>{@code tinyremapper.OutputConsumerPath} —— 「the jar file ... can't be written」；
 *       Fabric Loader 首次启动重映射游戏 jar 时崩溃</li>
 * </ul>
 *
 * <h3>本 agent 做两件事</h3>
 * <ol>
 *   <li>把 {@code sun.nio.fs.AbstractFileSystemProvider.isWritable} 改为恒返回 true
 *       —— 从根上修好判定，所有依赖它的组件一并恢复</li>
 *   <li>把 {@code jdk.nio.zipfs.ZipFileSystem.readOnly} 的赋值强制为 false
 *       —— 双保险，防止 ZipFileSystem 在 isWritable 被改之前就已加载</li>
 * </ol>
 *
 * <h3>为什么用 JDK 内置的 ASM 而不是打包一份</h3>
 * Fabric Loader 会主动校验 classpath，一旦发现重复的 ASM 类就直接拒绝启动：
 * <pre>
 *   IllegalStateException: duplicate ASM classes found on classpath
 *       at net.fabricmc.loader.impl.util.LoaderUtil.verifyClasspath
 * </pre>
 * 因此本 agent 改用 {@code jdk.internal.org.objectweb.asm}（JDK 自带），
 * jar 内不含任何 ASM，冲突从根上消失。
 *
 * <p>运行需要（编译时同样需要）：</p>
 * <pre>
 *   --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED
 * </pre>
 *
 * <p>安全性：把 isWritable 改为 true 只是让「预检」不再误报。若文件真的不可写，
 * 后续实际写入仍会抛出真实的 IOException —— 失败信息更准确，而非被掩盖。</p>
 */
public class ZipfsFixAgent {

	private static final String ZIPFS = "jdk/nio/zipfs/ZipFileSystem";
	private static final String AFSP = "sun/nio/fs/AbstractFileSystemProvider";

	public static void premain(String args, Instrumentation inst) {
		System.out.println("[EnvFixAgent] installed");
		System.out.println("[EnvFixAgent]   force " + AFSP + ".isWritable() -> true");
		System.out.println("[EnvFixAgent]   force " + ZIPFS + ".readOnly = false");

		inst.addTransformer(new ClassFileTransformer() {
			@Override
			public byte[] transform(ClassLoader loader,
			                        String className,
			                        Class<?> classBeingRedefined,
			                        ProtectionDomain protectionDomain,
			                        byte[] classfileBuffer) {
				if (ZIPFS.equals(className)) {
					return patchZipfs(classfileBuffer);
				}
				if (AFSP.equals(className)) {
					return patchIsWritable(classfileBuffer);
				}
				return null;
			}
		}, true);

		// AbstractFileSystemProvider 可能在 premain 之前就已加载，尝试重转换一次
		try {
			Class<?> afsp = Class.forName("sun.nio.fs.AbstractFileSystemProvider", false, null);
			inst.retransformClasses(afsp);
			System.out.println("[EnvFixAgent]   retransformed " + AFSP);
		} catch (Throwable t) {
			System.out.println("[EnvFixAgent]   retransform skipped: " + t);
		}
	}

	/** 把 isWritable(Path) 的方法体替换为 {@code return true;} */
	private static byte[] patchIsWritable(byte[] buffer) {
		try {
			ClassReader reader = new ClassReader(buffer);
			ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
			reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
				                                 String signature, String[] exceptions) {
					if ("isWritable".equals(name) && "(Ljava/nio/file/Path;)Z".equals(descriptor)) {
						// 取到下游 visitor 并直接写入 return true，
						// 返回 null 使 ASM 不再转发原有指令
						MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
						if (mv != null) {
							mv.visitCode();
							mv.visitInsn(Opcodes.ICONST_1);
							mv.visitInsn(Opcodes.IRETURN);
							mv.visitMaxs(1, 2);
							mv.visitEnd();
						}
						return null;
					}
					return super.visitMethod(access, name, descriptor, signature, exceptions);
				}
			}, 0);
			System.out.println("[EnvFixAgent]   patched " + AFSP);
			return writer.toByteArray();
		} catch (Throwable t) {
			System.err.println("[EnvFixAgent]   patch of " + AFSP + " FAILED: " + t);
			return null;
		}
	}

	/** 在 ZipFileSystem.<init> 里，每次给 readOnly 赋值后立即再写回 false */
	private static byte[] patchZipfs(byte[] buffer) {
		try {
			ClassReader reader = new ClassReader(buffer);
			ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
			reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
				                                 String signature, String[] exceptions) {
					MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
					if (!"<init>".equals(name)) {
						return mv;
					}
					return new MethodVisitor(Opcodes.ASM9, mv) {
						@Override
						public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
							super.visitFieldInsn(opcode, owner, fieldName, fieldDesc);
							if (opcode == Opcodes.PUTFIELD && ZIPFS.equals(owner) && "readOnly".equals(fieldName)) {
								// 栈平衡：this, 0 -> 被 PUTFIELD 消耗
								super.visitVarInsn(Opcodes.ALOAD, 0);
								super.visitInsn(Opcodes.ICONST_0);
								super.visitFieldInsn(Opcodes.PUTFIELD, owner, fieldName, fieldDesc);
							}
						}
					};
				}
			}, 0);
			System.out.println("[EnvFixAgent]   patched " + ZIPFS);
			return writer.toByteArray();
		} catch (Throwable t) {
			System.err.println("[EnvFixAgent]   patch of " + ZIPFS + " FAILED: " + t);
			return null;
		}
	}
}

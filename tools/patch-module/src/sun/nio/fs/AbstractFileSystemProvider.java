/*
 * Copyright (c) 2011, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * ===========================================================================
 *  本文件是 JDK 源码 sun/nio/fs/AbstractFileSystemProvider.java 的补丁版本。
 *
 *  改动只有一处：isWritable(Path) 的实现。
 *
 *  原因：本机（Windows 11 26200）上原实现永远返回 false，因为 checkAccess(WRITE)
 *  会抛异常并被吞掉：
 *
 *      public boolean isWritable(Path path) {
 *          try { checkAccess(path, AccessMode.WRITE); }
 *          catch (IOException e) { return false; }   // <- 异常被吞
 *          return true;
 *      }
 *
 *  而 WindowsFileSystemProvider.checkAccess(WRITE) 底层用
 *  GetEffectiveRightsFromAcl 计算 ACL 有效权限；该 Win32 函数不识别
 *  「登录会话关联的组权限」（如 Authenticated Users）与调用者特权，因而误判。
 *  实测：文件其实完全可写（写入成功、DOS 只读属性为 false、File.canWrite()=true）。
 *
 *  后果：所有依赖 Files.isWritable() 的组件全部误判为只读——
 *    - jdk.nio.zipfs.ZipFileSystem：readOnly 被置真，写 zip/jar 抛
 *      ReadOnlyFileSystemException（Fabric Loom 写 mappings 崩在这）
 *    - tinyremapper.OutputConsumerPath：「the jar file ... can't be written」
 *      （Fabric Loader 首次启动重映射游戏 jar 崩在这）
 *
 *  改为用 java.io.File.canWrite() 判断：它只检查 DOS 只读属性，在本机准确。
 *  文件不存在时退化为检查父目录是否可写，与原 checkAccess 的语义一致。
 *
 *  用法：编译后通过 --patch-module java.base=<输出目录> 替换该类。
 *  不用 Java agent、不用 ASM，因此不会与 Fabric Loader 的 classpath 校验冲突。
 * ===========================================================================
 */

package sun.nio.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessMode;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;

/**
 * Base implementation class of FileSystemProvider
 */

public abstract class AbstractFileSystemProvider extends FileSystemProvider {
    protected AbstractFileSystemProvider() { }

    /**
     * Splits the given attribute name into the name of an attribute view and
     * the attribute. If the attribute view is not identified then it assumed
     * to be "basic".
     */
    private static String[] split(String attribute) {
        String[] s = new String[2];
        int pos = attribute.indexOf(':');
        if (pos == -1) {
            s[0] = "basic";
            s[1] = attribute;
        } else {
            s[0] = attribute.substring(0, pos++);
            s[1] = (pos == attribute.length()) ? "" : attribute.substring(pos);
        }
        return s;
    }

    /**
     * Gets a DynamicFileAttributeView by name. Returns {@code null} if the
     * view is not available.
     */
    abstract DynamicFileAttributeView getFileAttributeView(Path file,
                                                           String name,
                                                           LinkOption... options);

    @Override
    public final void setAttribute(Path file,
                                   String attribute,
                                   Object value,
                                   LinkOption... options)
        throws IOException
    {
        String[] s = split(attribute);
        if (s[0].isEmpty())
            throw new IllegalArgumentException(attribute);
        DynamicFileAttributeView view = getFileAttributeView(file, s[0], options);
        if (view == null)
            throw new UnsupportedOperationException("View '" + s[0] + "' not available");
        view.setAttribute(s[1], value);
    }

    @Override
    public final Map<String,Object> readAttributes(Path file, String attributes, LinkOption... options)
        throws IOException
    {
        String[] s = split(attributes);
        if (s[0].isEmpty())
            throw new IllegalArgumentException(attributes);
        DynamicFileAttributeView view = getFileAttributeView(file, s[0], options);
        if (view == null)
            throw new UnsupportedOperationException("View '" + s[0] + "' not available");
        return view.readAttributes(s[1].split(","));
    }

    /**
     * Deletes a file. The {@code failIfNotExists} parameters determines if an
     * {@code IOException} is thrown when the file does not exist.
     */
    abstract boolean implDelete(Path file, boolean failIfNotExists) throws IOException;

    @Override
    public final void delete(Path file) throws IOException {
        implDelete(file, true);
    }

    @Override
    public final boolean deleteIfExists(Path file) throws IOException {
        return implDelete(file, false);
    }

    /**
     * Returns a path name as bytes for a Unix domain socket.
     * Different encodings may be used for these names on some platforms.
     * If path is empty, then an empty byte[] is returned.
     */
    public abstract byte[] getSunPathForSocketFile(Path path);

    /**
     * Tests whether a file is readable.
     */
    public boolean isReadable(Path path) {
        try {
            checkAccess(path, AccessMode.READ);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /**
     * Tests whether a file is writable.
     *
     * <p>补丁：原实现依赖 {@code checkAccess(WRITE)}，在本机会因
     * GetEffectiveRightsFromAcl 的缺陷恒定失败并被吞成 false。
     * 改用 {@link File#canWrite()}（只检查 DOS 只读属性，本机准确）；
     * 文件不存在时退化为检查父目录，与原语义一致。</p>
     */
    public boolean isWritable(Path path) {
        File file = path.toFile();
        if (file.exists()) {
            return file.canWrite();
        }
        File parent = file.getAbsoluteFile().getParentFile();
        return parent == null || parent.canWrite();
    }

    /**
     * Tests whether a file is executable.
     */
    public boolean isExecutable(Path path) {
        try {
            checkAccess(path, AccessMode.EXECUTE);
        } catch (IOException e) {
            return false;
        }
        return true;
    }
}

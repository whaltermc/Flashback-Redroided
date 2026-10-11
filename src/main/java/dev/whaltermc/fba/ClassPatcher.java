// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;

public final class ClassPatcher {

    private static final String GL_ARB_OLD =
            "GL_ARB_separate_shader_objects : require";
    private static final String GL_ARB_NEW =
            "GL_ARB_separate_shader_objects : warn";

    private static final String GLFW = "org/lwjgl/glfw/GLFW";
    private static final String SAFETY = "dev/whaltermc/fba/GlfwFallback";
    private static final String COMPAT = "dev/whaltermc/fba/GlfwWrapper";

    private static final java.util.Set<String> COMPAT_CALLS = java.util.Set.of(
            "glfwGetWindowAttrib(JI)I",
            "glfwGetMouseButton(JI)I",
            "glfwGetInputMode(JI)I",
            "glfwSetInputMode(JII)V",
            "glfwSetCursorPos(JDD)V",
            "glfwSetCursor(JJ)V",
            "glfwGetCursorPos(J[D[D)V",
            "glfwGetCursorPos(JLjava/nio/DoubleBuffer;Ljava/nio/DoubleBuffer;)V"
    );

    private ClassPatcher() {}

    public static byte[] transform(byte[] classBytes) {
        if (classBytes == null || classBytes.length == 0) {
            return classBytes;
        }

        boolean hasGlfw = contains(classBytes, GLFW);
        boolean hasGlArb = contains(classBytes, GL_ARB_OLD);

        if (!hasGlfw && !hasGlArb) {
            return classBytes;
        }

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(reader, 0);

            ClassVisitor chain = writer;

            if (hasGlArb) {
                chain = new GlArbFix(chain);
            }

            if (hasGlfw) {
                chain = new GlfwGuard(chain);
            }

            reader.accept(chain, 0);
            return writer.toByteArray();
        } catch (Throwable t) {
            // Never break class loading for another mod — return original bytes.
            return classBytes;
        }
    }

    private static final class GlArbFix extends ClassVisitor {

        GlArbFix(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null) return null;

            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitLdcInsn(Object cst) {
                    if (cst instanceof String s && s.contains(GL_ARB_OLD)) {
                        super.visitLdcInsn(s.replace(GL_ARB_OLD, GL_ARB_NEW));
                        return;
                    }
                    super.visitLdcInsn(cst);
                }
            };
        }
    }

    // Every other static GLFW call Flashback 0.39.10 makes, mirrored in
    // GlfwFallback. Calls outside both sets keep their owner so new LWJGL3
    // functions work natively on modern launchers.
    private static final java.util.Set<String> SAFE_CALLS = java.util.Set.of(
            "glfwCreateStandardCursor(I)J",
            "glfwCreateWindow(IILjava/lang/CharSequence;JJ)J",
            "glfwDestroyWindow(J)V",
            "glfwFocusWindow(J)V",
            "glfwGetCurrentContext()J",
            "glfwGetFramebufferSize(J[I[I)V",
            "glfwGetGamepadState(ILorg/lwjgl/glfw/GLFWGamepadState;)Z",
            "glfwGetKey(JI)I",
            "glfwGetKeyName(II)Ljava/lang/String;",
            "glfwGetKeyScancode(I)I",
            "glfwGetMonitorContentScale(J[F[F)V",
            "glfwGetMonitorPos(J[I[I)V",
            "glfwGetMonitorWorkarea(J[I[I[I[I)V",
            "glfwGetMonitors()Lorg/lwjgl/PointerBuffer;",
            "glfwGetTime()D",
            "glfwGetVideoMode(J)Lorg/lwjgl/glfw/GLFWVidMode;",
            "glfwGetWindowContentScale(J[F[F)V",
            "glfwGetWindowPos(J[I[I)V",
            "glfwGetWindowSize(J[I[I)V",
            "glfwHideWindow(J)V",
            "glfwMakeContextCurrent(J)V",
            "glfwSetCharModsCallback(JLorg/lwjgl/glfw/GLFWCharModsCallbackI;)Lorg/lwjgl/glfw/GLFWCharModsCallback;",
            "glfwSetCursorEnterCallback(JLorg/lwjgl/glfw/GLFWCursorEnterCallbackI;)Lorg/lwjgl/glfw/GLFWCursorEnterCallback;",
            "glfwSetCursorPosCallback(JLorg/lwjgl/glfw/GLFWCursorPosCallbackI;)Lorg/lwjgl/glfw/GLFWCursorPosCallback;",
            "glfwSetErrorCallback(Lorg/lwjgl/glfw/GLFWErrorCallbackI;)Lorg/lwjgl/glfw/GLFWErrorCallback;",
            "glfwSetKeyCallback(JLorg/lwjgl/glfw/GLFWKeyCallbackI;)Lorg/lwjgl/glfw/GLFWKeyCallback;",
            "glfwSetMonitorCallback(Lorg/lwjgl/glfw/GLFWMonitorCallbackI;)Lorg/lwjgl/glfw/GLFWMonitorCallback;",
            "glfwSetMouseButtonCallback(JLorg/lwjgl/glfw/GLFWMouseButtonCallbackI;)Lorg/lwjgl/glfw/GLFWMouseButtonCallback;",
            "glfwSetScrollCallback(JLorg/lwjgl/glfw/GLFWScrollCallbackI;)Lorg/lwjgl/glfw/GLFWScrollCallback;",
            "glfwSetWindowCloseCallback(JLorg/lwjgl/glfw/GLFWWindowCloseCallbackI;)Lorg/lwjgl/glfw/GLFWWindowCloseCallback;",
            "glfwSetWindowFocusCallback(JLorg/lwjgl/glfw/GLFWWindowFocusCallbackI;)Lorg/lwjgl/glfw/GLFWWindowFocusCallback;",
            "glfwSetWindowOpacity(JF)V",
            "glfwSetWindowPos(JII)V",
            "glfwSetWindowPosCallback(JLorg/lwjgl/glfw/GLFWWindowPosCallbackI;)Lorg/lwjgl/glfw/GLFWWindowPosCallback;",
            "glfwSetWindowSize(JII)V",
            "glfwSetWindowSizeCallback(JLorg/lwjgl/glfw/GLFWWindowSizeCallbackI;)Lorg/lwjgl/glfw/GLFWWindowSizeCallback;",
            "glfwSetWindowTitle(JLjava/lang/CharSequence;)V",
            "glfwShowWindow(J)V",
            "glfwSwapBuffers(J)V",
            "glfwSwapInterval(I)V",
            "glfwWindowHint(II)V"
    );

    private static final class GlfwGuard extends ClassVisitor {

        private boolean skip;

        GlfwGuard(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            // Never rewrite interfaces or our own classes: the mirrors call
            // real GLFW themselves and must not be rerouted into themselves.
            this.skip = (access & Opcodes.ACC_INTERFACE) != 0
                    || (name != null && name.startsWith("dev/whaltermc/fba/"));
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null || skip) return mv;

            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String mName,
                                            String mDesc, boolean itf) {
                    if (opcode == Opcodes.INVOKESTATIC && GLFW.equals(owner) && !itf) {
                        String key = mName + mDesc;
                        if (COMPAT_CALLS.contains(key)) {
                            super.visitMethodInsn(opcode, COMPAT, mName, mDesc, false);
                            return;
                        }
                        if (SAFE_CALLS.contains(key)) {
                            super.visitMethodInsn(opcode, SAFETY, mName, mDesc, false);
                            return;
                        }
                        // Unknown to both lists: leave it for native LWJGL3.
                    }
                    super.visitMethodInsn(opcode, owner, mName, mDesc, itf);
                }
            };
        }
    }

    private static boolean contains(byte[] data, String ascii) {
        byte[] needle = ascii.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}

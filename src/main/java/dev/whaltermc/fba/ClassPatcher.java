// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

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

    private record Stub(String glfwName, String desc, String stubName) {}

    private static final class GlfwGuard extends ClassVisitor {

        private final Map<String, Stub> stubs = new LinkedHashMap<>();
        private boolean isInterface;
        private String className;
        private boolean skip;

        GlfwGuard(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.className = name;
            this.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
            // Never inject stubs into interfaces or our own classes.
            this.skip = isInterface
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
                        Stub stub = stubs.computeIfAbsent(mName + mDesc,
                                k -> new Stub(mName, mDesc,
                                        "fba$glfw$" + mName + "$" + stubs.size()));
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, className,
                                stub.stubName(), stub.desc(), isInterface);
                        return;
                    }
                    super.visitMethodInsn(opcode, owner, mName, mDesc, itf);
                }
            };
        }

        @Override
        public void visitEnd() {
            for (Stub stub : stubs.values()) {
                emitStub(stub);
            }
            super.visitEnd();
        }

        private void emitStub(Stub stub) {
            Type[] args = Type.getArgumentTypes(stub.desc());
            Type ret = Type.getReturnType(stub.desc());

            MethodVisitor mv = super.visitMethod(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    stub.stubName(), stub.desc(), null, null);
            mv.visitCode();

            Label start = new Label();
            Label end = new Label();
            Label handler = new Label();
            mv.visitTryCatchBlock(start, end, handler, "java/lang/LinkageError");
            mv.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");

            mv.visitLabel(start);
            int slot = 0;
            Object[] frameLocals = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                mv.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slot);
                slot += args[i].getSize();
                frameLocals[i] = frameType(args[i]);
            }
            String target = COMPAT_CALLS.contains(stub.glfwName() + stub.desc()) ? COMPAT : GLFW;
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, target, stub.glfwName(), stub.desc(), false);
            mv.visitLabel(end);
            mv.visitInsn(returnOpcode(ret));

            mv.visitLabel(handler);
            mv.visitFrame(Opcodes.F_NEW, frameLocals.length, frameLocals,
                    1, new Object[]{"java/lang/Throwable"});
            mv.visitVarInsn(Opcodes.ASTORE, slot);
            mv.visitLdcInsn(stub.glfwName());
            mv.visitVarInsn(Opcodes.ALOAD, slot);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SAFETY, "missing",
                    "(Ljava/lang/String;Ljava/lang/Throwable;)V", false);
            pushZero(mv, ret);
            mv.visitInsn(returnOpcode(ret));

            mv.visitMaxs(slot + 2, slot + 1);
            mv.visitEnd();
        }

        private static Object frameType(Type t) {
            return switch (t.getSort()) {
                case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Opcodes.INTEGER;
                case Type.FLOAT -> Opcodes.FLOAT;
                case Type.LONG -> Opcodes.LONG;
                case Type.DOUBLE -> Opcodes.DOUBLE;
                case Type.ARRAY -> t.getDescriptor();
                default -> t.getInternalName();
            };
        }

        private static int returnOpcode(Type ret) {
            return ret.getSort() == Type.VOID ? Opcodes.RETURN : ret.getOpcode(Opcodes.IRETURN);
        }

        private static void pushZero(MethodVisitor mv, Type ret) {
            switch (ret.getSort()) {
                case Type.VOID -> { }
                case Type.LONG -> mv.visitInsn(Opcodes.LCONST_0);
                case Type.FLOAT -> mv.visitInsn(Opcodes.FCONST_0);
                case Type.DOUBLE -> mv.visitInsn(Opcodes.DCONST_0);
                case Type.OBJECT, Type.ARRAY -> mv.visitInsn(Opcodes.ACONST_NULL);
                default -> mv.visitInsn(Opcodes.ICONST_0);
            }
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

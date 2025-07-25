/*
 * Copyright (c) 2011, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package jdk.vm.ci.hotspot;

import static jdk.vm.ci.hotspot.CompilerToVM.compilerToVM;
import static jdk.vm.ci.hotspot.HotSpotJVMCIRuntime.runtime;
import static jdk.vm.ci.hotspot.HotSpotModifiers.BRIDGE;
import static jdk.vm.ci.hotspot.HotSpotModifiers.SYNTHETIC;
import static jdk.vm.ci.hotspot.HotSpotModifiers.VARARGS;
import static jdk.vm.ci.hotspot.HotSpotModifiers.jvmMethodModifiers;
import static jdk.vm.ci.hotspot.HotSpotResolvedJavaType.checkAreAnnotations;
import static jdk.vm.ci.hotspot.HotSpotResolvedJavaType.checkIsAnnotation;
import static jdk.vm.ci.hotspot.HotSpotResolvedJavaType.getFirstAnnotationOrNull;
import static jdk.vm.ci.hotspot.HotSpotVMConfig.config;
import static jdk.vm.ci.hotspot.UnsafeAccess.UNSAFE;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import jdk.internal.vm.VMSupport;
import jdk.vm.ci.common.JVMCIError;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime.Option;
import jdk.vm.ci.meta.AnnotationData;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.ConstantPool;
import jdk.vm.ci.meta.DefaultProfilingInfo;
import jdk.vm.ci.meta.ExceptionHandler;
import jdk.vm.ci.meta.JavaMethod;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.LineNumberTable;
import jdk.vm.ci.meta.Local;
import jdk.vm.ci.meta.LocalVariableTable;
import jdk.vm.ci.meta.ProfilingInfo;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.SpeculationLog;
import jdk.vm.ci.meta.TriState;

/**
 * Implementation of {@link JavaMethod} for resolved HotSpot methods.
 */
final class HotSpotResolvedJavaMethodImpl extends HotSpotMethod implements HotSpotResolvedJavaMethod, MetaspaceHandleObject {

    /**
     * A {@code jmetadata} value that is a handle to {@code Method*} value.
     */
    private final long methodHandle;

    private final HotSpotResolvedObjectTypeImpl holder;
    private final HotSpotConstantPool constantPool;
    final HotSpotSignature signature;
    private HotSpotMethodData methodData;
    private byte[] code;

    /**
     * Cache for {@link HotSpotJDKReflection#getMethod}.
     */
    volatile Executable toJavaCache;

    /**
     * Only 30% of {@link HotSpotResolvedJavaMethodImpl}s have their name accessed so compute it
     * lazily and cache it.
     */
    private String nameCache;

    /**
     * Gets the JVMCI mirror from a HotSpot method. The VM is responsible for ensuring that the
     * Method* is kept alive for the duration of this call and the {@link HotSpotJVMCIRuntime} keeps
     * it alive after that.
     * <p>
     * Called from the VM.
     *
     * @param metaspaceHandle a handle to metaspace Method object
     * @return the {@link ResolvedJavaMethod} corresponding to {@code metaspaceMethod}
     */
    @SuppressWarnings("unused")
    @VMEntryPoint
    private static HotSpotResolvedJavaMethod fromMetaspace(long metaspaceHandle, HotSpotResolvedObjectTypeImpl holder) {
        return holder.createMethod(metaspaceHandle);
    }

    HotSpotResolvedJavaMethodImpl(HotSpotResolvedObjectTypeImpl holder, long metaspaceHandle) {
        this.methodHandle = metaspaceHandle;
        this.holder = holder;

        HotSpotVMConfig config = config();
        final long constMethod = getConstMethod();

        /*
         * Get the constant pool from the metaspace method. Some methods (e.g. intrinsics for
         * signature-polymorphic method handle methods) have their own constant pool instead of the
         * one from their holder.
         */
        final long metaspaceConstantPool = UNSAFE.getAddress(constMethod + config.constMethodConstantsOffset);
        if (metaspaceConstantPool == holder.getConstantPool().getConstantPoolPointer()) {
            this.constantPool = holder.getConstantPool();
        } else {
            this.constantPool = compilerToVM().getConstantPool(this);
        }

        final int signatureIndex = UNSAFE.getChar(constMethod + config.constMethodSignatureIndexOffset);
        this.signature = (HotSpotSignature) constantPool.lookupSignature(signatureIndex);
        HandleCleaner.create(this, metaspaceHandle);
    }

    /**
     * Returns a pointer to this method's constant method data structure (
     * {@code Method::_constMethod}). This pointer isn't wrapped since it should be safe to use it
     * within the context of this HotSpotResolvedJavaMethodImpl since the Method* and ConstMethod*
     * are kept alive as a pair.
     *
     * @return pointer to this method's ConstMethod
     */
    private long getConstMethod() {
        return UNSAFE.getAddress(getMethodPointer() + config().methodConstMethodOffset);
    }

    @Override
    public String getName() {
        if (nameCache == null) {
            final int nameIndex = UNSAFE.getChar(getConstMethod() + config().constMethodNameIndexOffset);
            nameCache = constantPool.lookupUtf8(nameIndex);
        }
        return nameCache;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof HotSpotResolvedJavaMethodImpl) {
            HotSpotResolvedJavaMethodImpl that = (HotSpotResolvedJavaMethodImpl) obj;
            return that.getMethodPointer() == getMethodPointer();
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(getMethodPointer());
    }

    /**
     * Returns this method's flags ({@code Method::_flags}).
     *
     * @return flags of this method
     */
    private int getFlags() {
        return UNSAFE.getInt(getMethodPointer() + config().methodFlagsOffset);
    }

    /**
     * Returns this method's constant method flags ({@code ConstMethod::_flags}).
     *
     * @return flags of this method's ConstMethod
     */
    private int getConstMethodFlags() {
        return UNSAFE.getInt(getConstMethod() + config().constMethodFlagsOffset);
    }

    @Override
    public HotSpotResolvedObjectTypeImpl getDeclaringClass() {
        return holder;
    }

    /**
     * Gets the address of the C++ Method object for this method.
     */
    private Constant getMetaspaceMethodConstant() {
        return HotSpotMetaspaceConstantImpl.forMetaspaceObject(this, false);
    }

    long getMethodPointer() {
        long metaspacePointer = getMetaspacePointer();
        if (metaspacePointer == 0) {
            throw new NullPointerException("Method* is null");
        }
        return metaspacePointer;
    }

    @Override
    public long getMetadataHandle() {
        return methodHandle;
    }

    @Override
    public Constant getEncoding() {
        return getMetaspaceMethodConstant();
    }

    /**
     * Gets the complete set of modifiers for this method which includes the JVM specification
     * modifiers as well as the HotSpot internal modifiers.
     */
    public int getAllModifiers() {
        return UNSAFE.getInt(getMethodPointer() + config().methodAccessFlagsOffset);
    }

    @Override
    public int getModifiers() {
        return getAllModifiers() & jvmMethodModifiers();
    }

    @Override
    public boolean canBeStaticallyBound() {
        return (isFinal() || isPrivate() || isStatic() || holder.isLeaf() || isConstructor()) && isConcrete();
    }

    @Override
    public byte[] getCode() {
        if (getCodeSize() == 0) {
            return null;
        }
        if (code == null && holder.isLinked()) {
            code = compilerToVM().getBytecode(this);
            assert code.length == getCodeSize() : "expected: " + getCodeSize() + ", actual: " + code.length;
        }
        return code;
    }

    @Override
    public int getCodeSize() {
        int codeSize = UNSAFE.getChar(getConstMethod() + config().constMethodCodeSizeOffset);
        if (codeSize > 0 && !getDeclaringClass().isLinked()) {
            return -1;
        }
        return codeSize;
    }

    @Override
    public ExceptionHandler[] getExceptionHandlers() {
        final boolean hasExceptionTable = (getConstMethodFlags() & config().constMethodHasExceptionTable) != 0;
        if (!hasExceptionTable) {
            return new ExceptionHandler[0];
        }

        HotSpotVMConfig config = config();
        final int exceptionTableLength = compilerToVM().getExceptionTableLength(this);
        ExceptionHandler[] handlers = new ExceptionHandler[exceptionTableLength];
        long exceptionTableElement = compilerToVM().getExceptionTableStart(this);

        for (int i = 0; i < exceptionTableLength; i++) {
            final int startPc = UNSAFE.getChar(exceptionTableElement + config.exceptionTableElementStartPcOffset);
            final int endPc = UNSAFE.getChar(exceptionTableElement + config.exceptionTableElementEndPcOffset);
            final int handlerPc = UNSAFE.getChar(exceptionTableElement + config.exceptionTableElementHandlerPcOffset);
            int catchTypeIndex = UNSAFE.getChar(exceptionTableElement + config.exceptionTableElementCatchTypeIndexOffset);

            JavaType catchType;
            if (catchTypeIndex == 0) {
                catchType = null;
            } else {
                final int opcode = -1;  // opcode is not used
                catchType = constantPool.lookupType(catchTypeIndex, opcode);

                // Check for Throwable which catches everything.
                if (catchType instanceof HotSpotResolvedObjectTypeImpl) {
                    HotSpotResolvedObjectTypeImpl resolvedType = (HotSpotResolvedObjectTypeImpl) catchType;
                    if (resolvedType.equals(runtime().getJavaLangThrowable())) {
                        catchTypeIndex = 0;
                        catchType = null;
                    }
                }
            }
            handlers[i] = new ExceptionHandler(startPc, endPc, handlerPc, catchTypeIndex, catchType);

            // Go to the next ExceptionTableElement
            exceptionTableElement += config.exceptionTableElementSize;
        }

        return handlers;
    }

    /**
     * Returns true if this method has a {@code CallerSensitive} annotation.
     *
     * @return true if CallerSensitive annotation present, false otherwise
     */
    @Override
    public boolean isCallerSensitive() {
        return (getConstMethodFlags() & config().constMethodFlagsCallerSensitive) != 0;
    }

    /**
     * Returns true if this method has a {@code ForceInline} annotation.
     *
     * @return true if ForceInline annotation present, false otherwise
     */
    @Override
    public boolean isForceInline() {
        return (getFlags() & config().methodFlagsForceInline) != 0;
    }

    /**
     * Returns true if this method has a {@code ReservedStackAccess} annotation.
     *
     * @return true if ReservedStackAccess annotation present, false otherwise
     */
    @Override
    public boolean hasReservedStackAccess() {
        return (getConstMethodFlags() & config().constMethodFlagsReservedStackAccess) != 0;
    }

    /**
     * Returns true if this method has a
     * {@code jdk.internal.misc.ScopedMemoryAccess.Scoped} annotation.
     *
     * @return true if Scoped annotation present, false otherwise
     */
    @Override
    public boolean isScoped() {
        return (getConstMethodFlags() & config().constMethodFlagsIsScoped) != 0;
    }

    /**
     * Sets flags on {@code method} indicating that it should never be inlined or compiled by the
     * VM.
     */
    @Override
    public void setNotInlinableOrCompilable() {
        compilerToVM().setNotInlinableOrCompilable(this);
    }

    /**
     * Returns true if this method is one of the special methods that is ignored by security stack
     * walks.
     *
     * @return true if special method ignored by security stack walks, false otherwise
     */
    @Override
    public boolean ignoredBySecurityStackWalk() {
        return compilerToVM().methodIsIgnoredBySecurityStackWalk(this);
    }

    @Override
    public boolean isClassInitializer() {
        if (isStatic()) {
            final int nameIndex = UNSAFE.getChar(getConstMethod() + config().constMethodNameIndexOffset);
            long nameSymbol = constantPool.getEntryAt(nameIndex);
            long clinitSymbol = config().symbolClinit;
            return nameSymbol == clinitSymbol;
        }
        return false;
    }

    @Override
    public boolean isConstructor() {
        if (!isStatic()) {
            final int nameIndex = UNSAFE.getChar(getConstMethod() + config().constMethodNameIndexOffset);
            long nameSymbol = constantPool.getEntryAt(nameIndex);
            long initSymbol = config().symbolInit;
            return nameSymbol == initSymbol;
        }
        return false;
    }

    @Override
    public int getMaxLocals() {
        if (isAbstract() || isNative()) {
            return 0;
        }
        HotSpotVMConfig config = config();
        return UNSAFE.getChar(getConstMethod() + config.methodMaxLocalsOffset);
    }

    @Override
    public int getMaxStackSize() {
        if (isAbstract() || isNative()) {
            return 0;
        }
        HotSpotVMConfig config = config();
        return config.extraStackEntries + UNSAFE.getChar(getConstMethod() + config.constMethodMaxStackOffset);
    }

    @Override
    public StackTraceElement asStackTraceElement(int bci) {
        if (bci < 0 || bci >= getCodeSize()) {
            // HotSpot code can only construct stack trace elements for valid bcis
            StackTraceElement ste = compilerToVM().getStackTraceElement(this, 0);
            return new StackTraceElement(ste.getClassName(), ste.getMethodName(), ste.getFileName(), -1);
        }
        return compilerToVM().getStackTraceElement(this, bci);
    }

    @Override
    public ResolvedJavaMethod uniqueConcreteMethod(HotSpotResolvedObjectType receiver) {
        assert !canBeStaticallyBound() : this;

        if (receiver.isInterface()) {
            // Cannot trust interfaces. Because of:
            // interface I { void foo(); }
            // class A { public void foo() {} }
            // class B extends A implements I { }
            // class C extends B { public void foo() { } }
            // class D extends B { }
            // Would lead to identify C.foo() as the unique concrete method for I.foo() without
            // seeing A.foo().
            return null;
        }
        assert !receiver.isLinked() || isInVirtualMethodTable(receiver);
        if (this.isDefault()) {
            // CHA for default methods doesn't work and may crash the VM
            return null;
        }
        HotSpotResolvedObjectTypeImpl hsReceiver = (HotSpotResolvedObjectTypeImpl) receiver;
        return compilerToVM().findUniqueConcreteMethod(hsReceiver, this);
    }

    @Override
    public HotSpotSignature getSignature() {
        return signature;
    }

    /**
     * Gets the value of {@code Method::_code}.
     *
     * @return the value of {@code Method::_code}
     */
    private long getCompiledCode() {
        HotSpotVMConfig config = config();
        return UNSAFE.getAddress(getMethodPointer() + config.methodCodeOffset);
    }

    /**
     * Returns whether this method has compiled code.
     *
     * @return true if this method has compiled code, false otherwise
     */
    @Override
    public boolean hasCompiledCode() {
        return getCompiledCode() != 0L;
    }

    /**
     * @param level
     * @return true if the currently installed code was generated at {@code level}.
     */
    @Override
    public boolean hasCompiledCodeAtLevel(int level) {
        long compiledCode = getCompiledCode();
        if (compiledCode != 0) {
            return UNSAFE.getByte(compiledCode + config().nmethodCompLevelOffset) == level;
        }
        return false;
    }

    @Override
    public ProfilingInfo getProfilingInfo(boolean includeNormal, boolean includeOSR) {
        ProfilingInfo info;

        if (Option.UseProfilingInformation.getBoolean() && methodData == null) {
            long methodDataPointer = UNSAFE.getAddress(getMethodPointer() + config().methodDataOffset);
            if (methodDataPointer != 0) {
                methodData = new HotSpotMethodData(methodDataPointer, this);
                String methodDataFilter = Option.TraceMethodDataFilter.getString();
                if (methodDataFilter != null && this.format("%H.%n").contains(methodDataFilter)) {
                    String line = methodData.toString() + System.lineSeparator();
                    byte[] lineBytes = line.getBytes();
                    HotSpotJVMCIRuntime.runtime().writeDebugOutput(lineBytes, 0, lineBytes.length, true, true);
                }
            }
        }

        if (methodData == null || (!methodData.hasNormalData() && !methodData.hasExtraData())) {
            // Be optimistic and return false for exceptionSeen. A methodDataOop is allocated in
            // case of a deoptimization.
            info = DefaultProfilingInfo.get(TriState.FALSE);
        } else {
            info = new HotSpotProfilingInfoImpl(methodData, this, includeNormal, includeOSR);
        }
        return info;
    }

    @Override
    public void reprofile() {
        compilerToVM().reprofile(this);
    }

    @Override
    public ConstantPool getConstantPool() {
        return constantPool;
    }

    @Override
    public Parameter[] getParameters() {
        if (signature.getParameterCount(false) == 0) {
            return new ResolvedJavaMethod.Parameter[0];
        }
        return runtime().reflection.getParameters(this);
    }

    @Override
    public Annotation[][] getParameterAnnotations() {
        if ((getConstMethodFlags() & config().constMethodHasParameterAnnotations) == 0 || isClassInitializer()) {
            return new Annotation[signature.getParameterCount(false)][0];
        }
        return runtime().reflection.getParameterAnnotations(this);
    }

    @Override
    public Annotation[] getAnnotations() {
        if (!hasAnnotations()) {
            return new Annotation[0];
        }
        return runtime().reflection.getMethodAnnotations(this);
    }

    @Override
    public Annotation[] getDeclaredAnnotations() {
        if (!hasAnnotations()) {
            return new Annotation[0];
        }
        return runtime().reflection.getMethodDeclaredAnnotations(this);
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        if (!hasAnnotations()) {
            return null;
        }
        return runtime().reflection.getMethodAnnotation(this, annotationClass);
    }

    /**
     * Returns whether this method has annotations.
     */
    private boolean hasAnnotations() {
        return (getConstMethodFlags() & config().constMethodHasMethodAnnotations) != 0 && !isClassInitializer();
    }

    @Override
    public boolean isBridge() {
        return (BRIDGE & getModifiers()) != 0;
    }

    @Override
    public boolean isSynthetic() {
        return (SYNTHETIC & getModifiers()) != 0;
    }

    @Override
    public boolean isVarArgs() {
        return (VARARGS & getModifiers()) != 0;
    }

    @Override
    public boolean isDefault() {
        // Copied from java.lang.Method.isDefault()
        int mask = Modifier.ABSTRACT | Modifier.PUBLIC | Modifier.STATIC;
        return ((getModifiers() & mask) == Modifier.PUBLIC) && getDeclaringClass().isInterface();
    }

    /*
     * Currently in hotspot a method can either be a "normal" or an "overpass"
     * method. Overpass methods are instance methods which are created when
     * otherwise a valid candidate for method resolution would not be found.
     */
    @Override
    public boolean isDeclared() {
        if (isConstructor() || isClassInitializer()) {
            return false;
        }
        return (getConstMethodFlags() & config().constMethodFlagsIsOverpass) == 0;
    }

    @Override
    public Type[] getGenericParameterTypes() {
        if (isClassInitializer()) {
            return new Type[0];
        }
        return runtime().reflection.getGenericParameterTypes(this);
    }

    @Override
    public boolean canBeInlined() {
        if (isForceInline()) {
            return true;
        }
        if (hasNeverInlineDirective()) {
            return false;
        }
        return compilerToVM().isCompilable(this);
    }

    @Override
    public boolean hasNeverInlineDirective() {
        return compilerToVM().hasNeverInlineDirective(this);
    }

    @Override
    public boolean shouldBeInlined() {
        if (isForceInline()) {
            return true;
        }
        return compilerToVM().shouldInlineMethod(this);
    }

    @Override
    public LineNumberTable getLineNumberTable() {
        final boolean hasLineNumberTable = (getConstMethodFlags() & config().constMethodHasLineNumberTable) != 0;
        if (!hasLineNumberTable) {
            return null;
        }

        long[] values = compilerToVM().getLineNumberTable(this);
        if (values == null || values.length == 0) {
            // Empty table so treat is as non-existent
            return null;
        }
        assert values.length % 2 == 0;
        int[] bci = new int[values.length / 2];
        int[] line = new int[values.length / 2];

        for (int i = 0; i < values.length / 2; i++) {
            bci[i] = (int) values[i * 2];
            line[i] = (int) values[i * 2 + 1];
        }

        return new LineNumberTable(line, bci);
    }

    @Override
    public LocalVariableTable getLocalVariableTable() {
        final boolean hasLocalVariableTable = (getConstMethodFlags() & config().constMethodHasLocalVariableTable) != 0;
        if (!hasLocalVariableTable) {
            return null;
        }

        HotSpotVMConfig config = config();
        long localVariableTableElement = compilerToVM().getLocalVariableTableStart(this);
        final int localVariableTableLength = compilerToVM().getLocalVariableTableLength(this);
        Local[] locals = new Local[localVariableTableLength];

        for (int i = 0; i < localVariableTableLength; i++) {
            final int startBci = UNSAFE.getChar(localVariableTableElement + config.localVariableTableElementStartBciOffset);
            final int endBci = startBci + UNSAFE.getChar(localVariableTableElement + config.localVariableTableElementLengthOffset) - 1;
            final int nameCpIndex = UNSAFE.getChar(localVariableTableElement + config.localVariableTableElementNameCpIndexOffset);
            final int typeCpIndex = UNSAFE.getChar(localVariableTableElement + config.localVariableTableElementDescriptorCpIndexOffset);
            final int slot = UNSAFE.getChar(localVariableTableElement + config.localVariableTableElementSlotOffset);

            String localName = getConstantPool().lookupUtf8(nameCpIndex);
            String localType = getConstantPool().lookupUtf8(typeCpIndex);

            locals[i] = new Local(localName, runtime().lookupType(localType, holder, false), startBci, endBci, slot);

            // Go to the next LocalVariableTableElement
            localVariableTableElement += config.localVariableTableElementSize;
        }

        return new LocalVariableTable(locals);
    }

    /**
     * Returns the offset of this method into the v-table. The method must have a v-table entry as
     * indicated by {@link #isInVirtualMethodTable(ResolvedJavaType)}, otherwise an exception is
     * thrown.
     *
     * @return the offset of this method into the v-table
     */
    @Override
    public int vtableEntryOffset(ResolvedJavaType resolved) {
        if (!isInVirtualMethodTable(resolved)) {
            throw new JVMCIError("%s does not have a vtable entry in type %s", this, resolved);
        }
        HotSpotVMConfig config = config();
        final int vtableIndex = getVtableIndex((HotSpotResolvedObjectTypeImpl) resolved);
        return config.klassVtableStartOffset + vtableIndex * config.vtableEntrySize + config.vtableEntryMethodOffset;
    }

    @Override
    public boolean isInVirtualMethodTable(ResolvedJavaType resolved) {
        if (resolved instanceof HotSpotResolvedObjectTypeImpl) {
            HotSpotResolvedObjectTypeImpl hotspotResolved = (HotSpotResolvedObjectTypeImpl) resolved;
            int vtableIndex = getVtableIndex(hotspotResolved);
            return vtableIndex >= 0 && vtableIndex < hotspotResolved.getVtableLength();
        }
        return false;
    }

    private int getVtableIndex(HotSpotResolvedObjectTypeImpl resolved) {
        if (!holder.isLinked()) {
            return config().invalidVtableIndex;
        }
        if (holder.isInterface()) {
            if (resolved.isInterface() || !resolved.isLinked() || !getDeclaringClass().isAssignableFrom(resolved)) {
                return config().invalidVtableIndex;
            }
            return getVtableIndexForInterfaceMethod(resolved);
        }
        return getVtableIndex();
    }

    /**
     * Returns this method's virtual table index.
     *
     * @return virtual table index
     */
    private int getVtableIndex() {
        assert !holder.isInterface();
        HotSpotVMConfig config = config();
        int result = UNSAFE.getInt(getMethodPointer() + config.methodVtableIndexOffset);
        assert result >= config.nonvirtualVtableIndex : "must be linked";
        return result;
    }

    private int getVtableIndexForInterfaceMethod(ResolvedJavaType resolved) {
        HotSpotResolvedObjectTypeImpl hotspotType = (HotSpotResolvedObjectTypeImpl) resolved;
        return compilerToVM().getVtableIndexForInterfaceMethod(hotspotType, this);
    }

    @Override
    public SpeculationLog getSpeculationLog() {
        long address = compilerToVM().getFailedSpeculationsAddress(this);
        return new HotSpotSpeculationLog(address);
    }

    @Override
    public int intrinsicId() {
        HotSpotVMConfig config = config();
        return UNSAFE.getChar(getMethodPointer() + config.methodIntrinsicIdOffset);
    }

    @Override
    public boolean isIntrinsicCandidate() {
        return (getConstMethodFlags() & config().constMethodFlagsIntrinsicCandidate) != 0;
    }

    /**
     * Allocates a compile id for this method by asking the VM for one.
     *
     * @param entryBCI entry bci
     * @return compile id
     */
    @Override
    public int allocateCompileId(int entryBCI) {
        return compilerToVM().allocateCompileId(this, entryBCI);
    }

    @Override
    public boolean hasCodeAtLevel(int entryBCI, int level) {
        if (entryBCI == config().invocationEntryBci) {
            return hasCompiledCodeAtLevel(level);
        }
        return compilerToVM().hasCompiledCodeForOSR(this, entryBCI, level);
    }

    @Override
    public int methodIdnum() {
        return UNSAFE.getChar(getConstMethod() + config().constMethodMethodIdnumOffset);
    }

    @Override
    public AnnotationData getAnnotationData(ResolvedJavaType type) {
        if (!hasAnnotations()) {
            checkIsAnnotation(type);
            return null;
        }
        return getFirstAnnotationOrNull(getAnnotationData0(type));
    }

    @Override
    public List<AnnotationData> getAnnotationData(ResolvedJavaType type1, ResolvedJavaType type2, ResolvedJavaType... types) {
        checkIsAnnotation(type1);
        checkIsAnnotation(type2);
        checkAreAnnotations(types);
        if (!hasAnnotations()) {
            return List.of();
        }
        return getAnnotationData0(AnnotationDataDecoder.asArray(type1, type2, types));
    }

    private List<AnnotationData> getAnnotationData0(ResolvedJavaType... filter) {
        byte[] encoded = compilerToVM().getEncodedExecutableAnnotationData(this, filter);
        return VMSupport.decodeAnnotations(encoded, AnnotationDataDecoder.INSTANCE);
    }

    @Override
    public BitSet getOopMapAt(int bci) {
        if (getCodeSize() == 0) {
            throw new IllegalArgumentException("has no bytecode");
        }
        int nwords = ((getMaxLocals() + getMaxStackSize() - 1) / 64) + 1;
        long[] oopMap = new long[nwords];
        compilerToVM().getOopMapAt(this, bci, oopMap);
        return BitSet.valueOf(oopMap);
    }
}

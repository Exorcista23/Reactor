/*
 * Copyright (c) 2019-2022 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.tools.agent;

import java.util.concurrent.atomic.AtomicBoolean;

import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/**
 * Adds callSite info to every operator call (except "checkpoint").
 * Before:
 * <pre>
 *     Flux.just(1)
 *         .map(it -> it)
 * </pre>
 * After:
 * <pre>
 *     Flux flux = Hooks.addCallSiteInfo(Flux.just(1), "Flux.just -> MyClass.myMethod(MyClass.java:12)");
 *     flux = Hooks.addCallSiteInfo(flux.map(it -> it), "Flux.map -> MyClass.myMethod(MyClass.java:13)");
 * </pre>
 *
 */
class CallSiteInfoAddingMethodVisitor extends MethodVisitor {

    static final String ADD_CALLSITE_INFO_METHOD = "(Lorg/reactivestreams/Publisher;Ljava/lang/String;)Lorg/reactivestreams/Publisher;";

    /**
     * Determine if a class (in the {@code com/package/ClassName} format) can be considered a CorePublisher.
     *
     * @param className the class name in the {@code com/package/ClassName} format
     * @return true if it is exactly one of the CorePublisher interfaces (except ConnectableFlux), false otherwise
     */
    static boolean isCorePublisher(String className) {
        switch (className) {
            case "reactor/core/publisher/Flux":
            case "reactor/core/publisher/Mono":
            case "reactor/core/publisher/ParallelFlux":
            case "reactor/core/publisher/GroupedFlux":
                return true;
            default:
                return false;
        }
    }

    /**
     * Determine if a class (in the {@code com/package/ClassName} format) is compatible with lift or equivalent
     * wrapping done by Hooks. The return type of visited methods should be checked with this in order to
     * verify their result can be wrapped with debug traceback.
     *
     * @param className the class name in the {@code com/package/ClassName} format
     * @return true if it can be wrapped with debug traceback while maintaining the same type, false otherwise
     */
    static boolean isLiftCompatiblePublisher(String className) {
        return isCorePublisher(className) || "reactor/core/publisher/ConnectableFlux".equals(className);
    }

    final String currentMethod;

    final String currentClassName;

    final String currentSource;

    final AtomicBoolean changed;

    int currentLine = -1;

    CallSiteInfoAddingMethodVisitor(
            MethodVisitor visitor,
            String currentClassName,
            String currentMethod,
            String currentSource,
            AtomicBoolean changed
    ) {
        super(Opcodes.ASM9, visitor);
        this.currentMethod = currentMethod;
        this.currentClassName = currentClassName;
        this.currentSource = currentSource;
        this.changed = changed;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        super.visitLineNumber(line, start);
        currentLine = line;
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        if (isCorePublisher(owner)) {
            if ("checkpoint".equals(name)) {
                return;
            }
            String returnType = Type.getReturnType(descriptor).getInternalName();
            //Note this is the default path. If the return type is not lift-compatible (eg. MonoProcessor) then we shouldn't instrument it / wrap it
            if (!isLiftCompatiblePublisher(returnType)) {
                return;
            }

            changed.set(true);

            String callSite = String.format(
                    "\t%s.%s\n\t%s.%s(%s:%d)\n",
                    owner.replace("/", "."), name,
                    currentClassName.replace("/", "."), currentMethod, currentSource, currentLine
            );
            super.visitLdcInsn(callSite);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "reactor/core/publisher/Hooks",
                    "addCallSiteInfo",
                    ADD_CALLSITE_INFO_METHOD,
                    false
            );
            super.visitTypeInsn(Opcodes.CHECKCAST, returnType);
        }
    }
}

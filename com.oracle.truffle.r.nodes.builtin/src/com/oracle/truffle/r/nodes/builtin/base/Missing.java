/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.r.nodes.builtin.base;

import static com.oracle.truffle.r.runtime.RBuiltinKind.*;

import java.util.function.*;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.utilities.*;
import com.oracle.truffle.r.nodes.*;
import com.oracle.truffle.r.nodes.access.ConstantNode;
import com.oracle.truffle.r.nodes.builtin.*;
import com.oracle.truffle.r.nodes.function.*;
import com.oracle.truffle.r.runtime.*;
import com.oracle.truffle.r.runtime.data.*;
import com.oracle.truffle.r.runtime.data.RPromise.PromiseProfile;

@RBuiltin(name = "missing", kind = PRIMITIVE, parameterNames = {"x"}, nonEvalArgs = {0})
public abstract class Missing extends RBuiltinNode {

    @Child private InlineCacheNode<Frame, String> repCache;

    private final ConditionProfile isSymbolNullProfile = ConditionProfile.createBinaryProfile();

    private static InlineCacheNode<Frame, String> createRepCache(int level) {
        Function<String, RNode> reify = symbol -> createNodeForRep(symbol, level);
        BiFunction<Frame, String, Object> generic = (frame, symbol) -> RRuntime.asLogical(RMissingHelper.isMissingArgument(frame, symbol));
        return InlineCacheNode.create(3, reify, generic);
    }

    private static RNode createNodeForRep(String symbol, int level) {
        if (symbol == null) {
            return ConstantNode.create(RRuntime.LOGICAL_FALSE);
        }
        return new MissingCheckLevel(symbol, level);
    }

    private static class MissingCheckLevel extends RNode {

        @Child private GetMissingValueNode getMissingValue;
        @Child private InlineCacheNode<Frame, String> recursive;

        private final ConditionProfile isNullProfile = ConditionProfile.createBinaryProfile();
        private final ConditionProfile isMissingProfile = ConditionProfile.createBinaryProfile();
        private final ConditionProfile isPromiseProfile = ConditionProfile.createBinaryProfile();
        private final ConditionProfile isSymbolNullProfile = ConditionProfile.createBinaryProfile();
        private final PromiseProfile promiseProfile = new PromiseProfile();
        private final int level;

        public MissingCheckLevel(String symbol, int level) {
            this.level = level;
            this.getMissingValue = GetMissingValueNode.create(symbol);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            // Read symbols value directly
            Object value = getMissingValue.execute(frame);
            if (isNullProfile.profile(value == null)) {
                // In case we are not able to read the symbol in current frame: This is not an
                // argument and thus return false
                return RRuntime.LOGICAL_FALSE;
            }

            if (isMissingProfile.profile(value == RMissing.instance)) {
                return RRuntime.LOGICAL_TRUE;
            }

            assert level < 30;
            // This might be a promise...
            if (isPromiseProfile.profile(value instanceof RPromise)) {
                RPromise promise = (RPromise) value;
                if (level == 0 && promise.isDefault(promiseProfile)) {
                    return RRuntime.LOGICAL_TRUE;
                }
                if (level > 0 && promise.isEvaluated(promiseProfile)) {
                    return RRuntime.LOGICAL_FALSE;
                }
                if (recursive == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    recursive = insert(createRepCache(level + 1));
                }
                // Check: If there is a cycle, return true. (This is done like in GNU R)
                if (promise.isUnderEvaluation(promiseProfile)) {
                    return RRuntime.LOGICAL_TRUE;
                }
                try {
                    promise.setUnderEvaluation(true);
                    String symbol = RMissingHelper.unwrapName((RNode) promise.getRep());
                    return isSymbolNullProfile.profile(symbol == null) ? RRuntime.LOGICAL_FALSE : recursive.execute(promise.getFrame(), symbol);
                } finally {
                    promise.setUnderEvaluation(false);
                }
            }
            return RRuntime.LOGICAL_FALSE;
        }
    }

    @Specialization
    protected byte missing(VirtualFrame frame, RPromise promise) {
        controlVisibility();
        if (repCache == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            repCache = insert(createRepCache(0));
        }
        String symbol = RMissingHelper.unwrapName((RNode) promise.getRep());
        return isSymbolNullProfile.profile(symbol == null) ? RRuntime.LOGICAL_FALSE : (byte) repCache.execute(frame, symbol);
    }

    @Specialization
    protected byte missing(@SuppressWarnings("unused") RMissing obj) {
        controlVisibility();
        return RRuntime.LOGICAL_TRUE;
    }

    @Fallback
    protected byte missing(@SuppressWarnings("unused") Object obj) {
        controlVisibility();
        return RRuntime.LOGICAL_FALSE;
    }
}

/*
 * Copyright (c) 2013, 2016, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.truffle.r.runtime.RDispatch.SUMMARY_GROUP_GENERIC;
import static com.oracle.truffle.r.runtime.builtins.RBehavior.PURE;
import static com.oracle.truffle.r.runtime.builtins.RBuiltinKind.PRIMITIVE;
import static com.oracle.truffle.r.nodes.builtin.CastBuilder.Predef.*;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.r.nodes.builtin.CastBuilder;
import com.oracle.truffle.r.nodes.builtin.RBuiltinNode;
import com.oracle.truffle.r.nodes.unary.CastLogicalNode;
import com.oracle.truffle.r.nodes.unary.CastLogicalNodeGen;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.builtins.RBuiltin;
import com.oracle.truffle.r.runtime.data.RArgsValuesAndNames;
import com.oracle.truffle.r.runtime.data.RLogicalVector;
import com.oracle.truffle.r.runtime.data.RMissing;
import com.oracle.truffle.r.runtime.data.RNull;
import com.oracle.truffle.r.runtime.data.RSequence;
import com.oracle.truffle.r.runtime.data.RVector;

/**
 * TODO: Added primitive {@code na.rm} support, but this code needs rewriting in the same manner as
 * {@link Any} and there is opportunity to share code.
 */
@RBuiltin(name = "all", kind = PRIMITIVE, parameterNames = {"...", "na.rm"}, dispatch = SUMMARY_GROUP_GENERIC, behavior = PURE)
public abstract class All extends RBuiltinNode {

    @Child private CastLogicalNode castLogicalNode;

    @Override
    public Object[] getDefaultParameterValues() {
        return new Object[]{RArgsValuesAndNames.EMPTY, RRuntime.LOGICAL_FALSE};
    }

    @Override
    protected void createCasts(CastBuilder casts) {
        // casts.arg("...").mustBe(integerValue().or(logicalValue())).asLogicalVector();
        casts.arg("na.rm").asLogicalVector().findFirst(RRuntime.LOGICAL_NA).map(toBoolean());
    }

    @Specialization
    protected byte all(byte value, @SuppressWarnings("unused") boolean naRm) {
        return value;
    }

    @Specialization
    protected byte all(RLogicalVector vector, boolean naRm) {
        return accumulate(vector, naRm);
    }

    @Specialization
    protected byte all(@SuppressWarnings("unused") RNull vector, @SuppressWarnings("unused") boolean naRm) {
        return RRuntime.LOGICAL_TRUE;
    }

    @Specialization
    protected byte all(@SuppressWarnings("unused") RMissing vector, @SuppressWarnings("unused") boolean naRm) {
        return RRuntime.LOGICAL_TRUE;
    }

    @Specialization
    protected byte all(RArgsValuesAndNames args, boolean naRm) {
        if (castLogicalNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            castLogicalNode = insert(CastLogicalNodeGen.create(true, false, false));
        }
        Object[] argValues = args.getArguments();
        for (Object argValue : argValues) {
            byte result;
            if (argValue instanceof RVector || argValue instanceof RSequence) {
                result = accumulate((RLogicalVector) castLogicalNode.execute(argValue), naRm);
            } else if (argValue == RNull.instance) {
                result = RRuntime.LOGICAL_TRUE;
            } else {
                result = (byte) castLogicalNode.execute(argValue);
                if (result == RRuntime.LOGICAL_NA && naRm) {
                    continue;
                }
            }
            if (result != RRuntime.LOGICAL_TRUE) {
                return result;
            }
        }
        return RRuntime.LOGICAL_TRUE;
    }

    private static byte accumulate(RLogicalVector vector, boolean naRm) {
        for (int i = 0; i < vector.getLength(); i++) {
            byte b = vector.getDataAt(i);
            if (b == RRuntime.LOGICAL_NA && naRm) {
                continue;
            }
            if (b != RRuntime.LOGICAL_TRUE) {
                return b;
            }
        }
        return RRuntime.LOGICAL_TRUE;
    }
}

/*
 * This material is distributed under the GNU General Public License
 * Version 2. You may review the terms of this license at
 * http://www.gnu.org/licenses/gpl-2.0.html
 *
 * Copyright (c) 2014, Purdue University
 * Copyright (c) 2014, Oracle and/or its affiliates
 *
 * All rights reserved.
 */
package com.oracle.truffle.r.nodes.builtin.base;

import static com.oracle.truffle.r.runtime.RBuiltinKind.*;

import java.util.*;

import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.r.nodes.builtin.*;
import com.oracle.truffle.r.nodes.unary.*;
import com.oracle.truffle.r.runtime.*;
import com.oracle.truffle.r.runtime.data.*;
import com.oracle.truffle.r.runtime.ops.na.*;

@RBuiltin(name = "cummin", kind = PRIMITIVE)
public abstract class CumMin extends RBuiltinNode {

    private final NACheck na = NACheck.create();

    @Specialization
    public double cummin(double arg) {
        controlVisibility();
        return arg;
    }

    @Specialization
    public int cummin(int arg) {
        controlVisibility();
        return arg;
    }

    @Specialization
    public int cummin(byte arg) {
        controlVisibility();
        na.enable(arg);
        if (na.check(arg)) {
            return RRuntime.INT_NA;
        }
        return arg;
    }

    @Specialization
    public double cummax(String arg) {
        controlVisibility();
        return CastDoubleNodeFactory.create(null, false, false, false).doString(arg);
    }

    @Specialization
    public RIntVector cumMin(RIntSequence v) {
        controlVisibility();
        int[] cminV = new int[v.getLength()];

        if (v.getStride() > 0) {
            // all numbers are bigger than the first one
            Arrays.fill(cminV, v.getStart());
            return RDataFactory.createIntVector(cminV, RDataFactory.COMPLETE_VECTOR, v.getNames());
        } else {
            cminV[0] = v.getStart();
            for (int i = 1; i < v.getLength(); i++) {
                cminV[i] = cminV[i - 1] + v.getStride();
            }
            return RDataFactory.createIntVector(cminV, RDataFactory.COMPLETE_VECTOR, v.getNames());
        }
    }

    @Specialization
    public RDoubleVector cummin(RDoubleVector v) {
        controlVisibility();
        double[] cminV = new double[v.getLength()];
        double min = v.getDataAt(0);
        cminV[0] = min;
        na.enable(v);
        int i;
        for (i = 1; i < v.getLength(); ++i) {
            if (v.getDataAt(i) < min) {
                min = v.getDataAt(i);
            }
            if (na.check(v.getDataAt(i))) {
                break;
            }
            cminV[i] = min;
        }
        if (!na.neverSeenNA()) {
            Arrays.fill(cminV, i, cminV.length, RRuntime.DOUBLE_NA);
        }
        return RDataFactory.createDoubleVector(cminV, na.neverSeenNA(), v.getNames());
    }

    @Specialization
    public RIntVector cummin(RIntVector v) {
        controlVisibility();
        int[] cminV = new int[v.getLength()];
        int min = v.getDataAt(0);
        cminV[0] = min;
        na.enable(v);
        int i;
        for (i = 1; i < v.getLength(); ++i) {
            if (v.getDataAt(i) < min) {
                min = v.getDataAt(i);
            }
            if (na.check(v.getDataAt(i))) {
                break;
            }
            cminV[i] = min;
        }
        if (!na.neverSeenNA()) {
            Arrays.fill(cminV, i, cminV.length, RRuntime.INT_NA);
        }
        return RDataFactory.createIntVector(cminV, na.neverSeenNA(), v.getNames());
    }

    @Specialization
    public RIntVector cummin(RLogicalVector v) {
        controlVisibility();
        int[] cminV = new int[v.getLength()];
        int min = v.getDataAt(0);
        cminV[0] = min;
        na.enable(v);
        int i;
        for (i = 1; i < v.getLength(); ++i) {
            if (v.getDataAt(i) < min) {
                min = v.getDataAt(i);
            }
            if (na.check(v.getDataAt(i))) {
                break;
            }
            cminV[i] = min;
        }
        if (!na.neverSeenNA()) {
            Arrays.fill(cminV, i, cminV.length, RRuntime.INT_NA);
        }
        return RDataFactory.createIntVector(cminV, na.neverSeenNA(), v.getNames());
    }

    @Specialization
    public RDoubleVector cummin(VirtualFrame frame, RStringVector v) {
        controlVisibility();
        return cummin((RDoubleVector) CastDoubleNodeFactory.create(null, false, false, false).executeDouble(frame, v));
    }

    @Specialization
    public RComplexVector cummin(@SuppressWarnings("unused") RComplexVector v) {
        controlVisibility();
        throw RError.error(getEncapsulatingSourceSection(), RError.Message.CUMMIN_UNDEFINED_FOR_COMPLEX);
    }

}
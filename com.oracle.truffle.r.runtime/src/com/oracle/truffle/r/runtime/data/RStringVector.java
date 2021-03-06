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
package com.oracle.truffle.r.runtime.data;

import java.util.Arrays;

import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.r.runtime.RInternalError;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.RType;
import com.oracle.truffle.r.runtime.Utils;
import com.oracle.truffle.r.runtime.data.closures.RClosures;
import com.oracle.truffle.r.runtime.data.model.RAbstractStringVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractVector;
import com.oracle.truffle.r.runtime.ops.na.NACheck;

public final class RStringVector extends RVector implements RAbstractStringVector {

    public static final RStringVector implicitClassHeader = RDataFactory.createStringVectorFromScalar(RType.Character.getClazz());

    private final String[] data;

    RStringVector(String[] data, boolean complete, int[] dims, RStringVector names) {
        super(complete, data.length, dims, names);
        this.data = data;
        assert verify();
    }

    private RStringVector(String[] data, boolean complete, int[] dims) {
        this(data, complete, dims, null);
    }

    @Override
    public RAbstractVector castSafe(RType type, ConditionProfile isNAProfile) {
        switch (type) {
            case Character:
                return this;
            case List:
                return RClosures.createAbstractVectorToListVector(this);
            default:
                return null;
        }
    }

    @Override
    public String[] getInternalStore() {
        return data;
    }

    @Override
    public void setDataAt(Object store, int index, String value) {
        assert data == store;
        ((String[]) store)[index] = value;
    }

    @Override
    public String getDataAt(Object store, int index) {
        assert data == store;
        return ((String[]) store)[index];
    }

    @Override
    protected RStringVector internalCopy() {
        return new RStringVector(Arrays.copyOf(data, data.length), isComplete(), null);
    }

    @Override
    public int getLength() {
        return data.length;
    }

    public String[] getDataCopy() {
        String[] copy = new String[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        return copy;
    }

    /**
     * Intended for external calls where a copy is not needed. WARNING: think carefully before using
     * this method rather than {@link #getDataCopy()}.
     */
    public String[] getDataWithoutCopying() {
        return data;
    }

    /**
     * Return vector data (copying if necessary) that's guaranteed not to be shared with any other
     * vector instance (but maybe non-temporary in terms of vector's sharing mode).
     *
     * @return vector data
     */
    public String[] getDataNonShared() {
        return isShared() ? getDataCopy() : getDataWithoutCopying();

    }

    /**
     * Return vector data (copying if necessary) that's guaranteed to be "fresh" (temporary in terms
     * of vector sharing mode).
     *
     * @return vector data
     */
    public String[] getDataTemp() {
        return isTemporary() ? getDataWithoutCopying() : getDataCopy();
    }

    @Override
    public String toString() {
        return toString(i -> getDataAt(i));
    }

    @Override
    protected boolean internalVerify() {
        if (isComplete()) {
            for (String b : data) {
                if (b == RRuntime.STRING_NA) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public String getDataAt(int i) {
        return data[i];
    }

    @Override
    protected String getDataAtAsString(int index) {
        return getDataAt(index);
    }

    public RStringVector updateDataAt(int i, String right, NACheck rightNACheck) {
        if (this.isShared()) {
            throw RInternalError.shouldNotReachHere("update shared vector");
        }
        data[i] = right;
        if (rightNACheck.check(right)) {
            setComplete(false);
        }
        assert !isComplete() || !RRuntime.isNA(right);
        return this;
    }

    @Override
    public RStringVector updateDataAtAsObject(int i, Object o, NACheck naCheck) {
        return updateDataAt(i, (String) o, naCheck);

    }

    private String[] copyResizedData(int size, String fill) {
        String[] newData = Arrays.copyOf(data, size);
        if (size > this.getLength()) {
            if (fill != null) {
                for (int i = data.length; i < size; i++) {
                    newData[i] = fill;
                }
            } else {
                for (int i = data.length, j = 0; i < size; ++i, j = Utils.incMod(j, data.length)) {
                    newData[i] = data[j];
                }
            }
        }
        return newData;
    }

    private String[] createResizedData(int size, String fill) {
        return copyResizedData(size, fill);
    }

    @Override
    protected RStringVector internalCopyResized(int size, boolean fillNA) {
        boolean isComplete = isComplete() && ((data.length >= size) || !fillNA);
        return RDataFactory.createStringVector(copyResizedData(size, fillNA ? RRuntime.STRING_NA : null), isComplete);
    }

    public RStringVector resizeWithEmpty(int size) {
        return RDataFactory.createStringVector(createResizedData(size, RRuntime.NAMES_ATTR_EMPTY_VALUE), isComplete());
    }

    @Override
    public RStringVector createEmptySameType(int newLength, boolean newIsComplete) {
        return RDataFactory.createStringVector(new String[newLength], newIsComplete);
    }

    @Override
    public void transferElementSameType(int toIndex, RAbstractVector fromVector, int fromIndex) {
        RAbstractStringVector other = (RAbstractStringVector) fromVector;
        data[toIndex] = other.getDataAt(fromIndex);
    }

    @Override
    public RStringVector copyWithNewDimensions(int[] newDimensions) {
        return RDataFactory.createStringVector(data, isComplete(), newDimensions);
    }

    @Override
    public RStringVector materialize() {
        return this;
    }

    @Override
    public Object getDataAtAsObject(int index) {
        return getDataAt(index);
    }

    @Override
    public RStringVector getImplicitClass() {
        return getClassHierarchyHelper(implicitClassHeader);
    }

    @Override
    public void setElement(int i, Object value) {
        data[i] = (String) value;
    }
}

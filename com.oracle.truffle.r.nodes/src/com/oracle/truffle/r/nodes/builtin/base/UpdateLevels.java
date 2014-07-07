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

import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.r.nodes.builtin.*;
import com.oracle.truffle.r.runtime.*;
import com.oracle.truffle.r.runtime.data.*;
import com.oracle.truffle.r.runtime.data.model.*;

@RBuiltin(name = "levels<-", kind = RBuiltinKind.PRIMITIVE)
public abstract class UpdateLevels extends RInvisibleBuiltinNode {

    @Specialization
    public RAbstractVector updateLevels(RAbstractVector vector, @SuppressWarnings("unused") RNull levels) {
        controlVisibility();
        RVector v = vector.materialize();
        v.setLevels(null);
        return v;
    }

    @Specialization
    public RAbstractVector updateLevels(RAbstractVector vector, Object levels) {
        controlVisibility();
        RVector v = vector.materialize();
        v.setLevels(levels);
        return v;

    }
}
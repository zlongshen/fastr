/*
 * This material is distributed under the GNU General Public License
 * Version 2. You may review the terms of this license at
 * http://www.gnu.org/licenses/gpl-2.0.html
 *
 * Copyright (C) 1998 Ross Ihaka
 * Copyright (c) 2000--2009, The R Core Team
 * Copyright (c) 2003--2009, The R Foundation
 * Copyright (c) 2016, 2016, Oracle and/or its affiliates
 *
 * All rights reserved.
 */
package com.oracle.truffle.r.library.stats;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.r.nodes.builtin.CastBuilder;
import com.oracle.truffle.r.nodes.builtin.RExternalBuiltinNode;
import com.oracle.truffle.r.nodes.profile.VectorLengthProfile;
import com.oracle.truffle.r.runtime.RError;
import com.oracle.truffle.r.runtime.RError.Message;
import com.oracle.truffle.r.runtime.data.RDataFactory;
import com.oracle.truffle.r.runtime.data.model.RAbstractDoubleVector;
import com.oracle.truffle.r.runtime.nodes.RNode;
import com.oracle.truffle.r.runtime.ops.na.NAProfile;
import com.oracle.truffle.r.runtime.rng.RRNG;

// transcribed from rbinom.c

public abstract class Rbinom extends RExternalBuiltinNode.Arg3 {

    @TruffleBoundary
    private static double unifRand() {
        return RRNG.unifRand();
    }

    private final Qbinom qbinom = new Qbinom();

    double rbinom(double nin, double pp, BranchProfile nanProfile) {
        double psave = -1.0;
        int nsave = -1;

        if (!Double.isFinite(nin)) {
            nanProfile.enter();
            return Double.NaN;
        }
        double r = MathConstants.forceint(nin);
        if (r != nin) {
            nanProfile.enter();
            return Double.NaN;
        }
        /* n=0, p=0, p=1 are not errors <TSL> */
        if (!Double.isFinite(pp) || r < 0 || pp < 0. || pp > 1.) {
            nanProfile.enter();
            return Double.NaN;
        }

        if (r == 0 || pp == 0.) {
            return 0;
        }
        if (pp == 1.) {
            return r;
        }

        if (r >= Integer.MAX_VALUE) {
            /*
             * evade integer overflow, and r == INT_MAX gave only even values
             */
            return qbinom.evaluate(unifRand(), r, pp, /* lower_tail */false, /* log_p */false);
        }
        /* else */
        int n = (int) r;

        double p = Math.min(pp, 1. - pp);
        double q = 1. - p;
        double np = n * p;
        r = p / q;
        double g = r * (n + 1);

        /* Setup, perform only when parameters change [using static (globals): */

        /*
         * FIXING: Want this thread safe -- use as little (thread globals) as possible
         */
        int ix;
        double qn = 0;
        double f;
        double u;
        double v;
        double x;
        double al;
        double amaxp;
        double ynorm;
        double alv;
        finis: do {
            L_np_small: do {
                int m;
                double c;
                double fm;
                double npq;
                double p1;
                double p2;
                double p3;
                double p4;
                double xl;
                double xll;
                double xlr;
                double xm;
                double xr;
                if (pp != psave || n != nsave) {
                    psave = pp;
                    nsave = n;
                    if (np < 30.0) {
                        /* inverse cdf logic for mean less than 30 */
                        qn = Arithmetic.powDi(q, n);
                        // goto L_np_small;
                        break L_np_small;
                    } else {
                        double ffm = np + p;
                        m = (int) ffm;
                        fm = m;
                        npq = np * q;
                        p1 = (int) (2.195 * Math.sqrt(npq) - 4.6 * q) + 0.5;
                        xm = fm + 0.5;
                        xl = xm - p1;
                        xr = xm + p1;
                        c = 0.134 + 20.5 / (15.3 + fm);
                        al = (ffm - xl) / (ffm - xl * p);
                        xll = al * (1.0 + 0.5 * al);
                        al = (xr - ffm) / (xr * q);
                        xlr = al * (1.0 + 0.5 * al);
                        p2 = p1 * (1.0 + c + c);
                        p3 = p2 + c / xll;
                        p4 = p3 + c / xlr;
                    }
                } else { /* if (n == nsave) */
                    /*
                     * lstadler: turned these ifs into asserts, otherwise the code structure isn't
                     * sound
                     */
                    assert n == nsave && np < 30.0;
                    // if (np < 30.0) {
                    // goto L_np_small;
                    break L_np_small;
                    // }
                }

                /*-------------------------- np = n*p >= 30 : ------------------- */
                while (true) {
                    u = unifRand() * p4;
                    v = unifRand();
                    /* triangular region */
                    if (u <= p1) {
                        ix = (int) (xm - p1 * v + u);
                        // goto finis;
                        break finis;
                    }
                    /* parallelogram region */
                    if (u <= p2) {
                        x = xl + (u - p1) / c;
                        v = v * c + 1.0 - Math.abs(xm - x) / p1;
                        if (v > 1.0 || v <= 0.) {
                            continue;
                        }
                        ix = (int) x;
                    } else {
                        if (u > p3) { /* right tail */
                            ix = (int) (xr - Math.log(v) / xlr);
                            if (ix > n) {
                                continue;
                            }
                            v = v * (u - p3) * xlr;
                        } else {
                            /* left tail */
                            ix = (int) (xl + Math.log(v) / xll);
                            if (ix < 0) {
                                continue;
                            }
                            v = v * (u - p2) * xll;
                        }
                    }
                    /* determine appropriate way to perform accept/reject test */
                    int k = Math.abs(ix - m);
                    if (k <= 20 || k >= npq / 2 - 1) {
                        /* explicit evaluation */
                        f = 1.0;
                        if (m < ix) {
                            for (int i = m + 1; i <= ix; i++) {
                                f *= (g / i - r);
                            }
                        } else if (m != ix) {
                            for (int i = ix + 1; i <= m; i++) {
                                f /= (g / i - r);
                            }
                        }
                        if (v <= f) {
                            // goto finis;
                            break finis;
                        }
                    } else {
                        /* squeezing using upper and lower bounds on log(f(x)) */
                        amaxp = (k / npq) * ((k * (k / 3. + 0.625) + 0.1666666666666) / npq + 0.5);
                        ynorm = -k * k / (2.0 * npq);
                        alv = Math.log(v);
                        if (alv < ynorm - amaxp) {
                            // goto finis;
                            break finis;
                        }
                        if (alv <= ynorm + amaxp) {
                            /* stirling's formula to machine accuracy */
                            /* for the final acceptance/rejection test */
                            double x1 = ix + 1;
                            double f1 = fm + 1.0;
                            double z = n + 1 - fm;
                            double w = n - ix + 1.0;
                            double z2 = z * z;
                            double x2 = x1 * x1;
                            double f2 = f1 * f1;
                            double w2 = w * w;
                            if (alv <= xm * Math.log(f1 / x1) + (n - m + 0.5) * Math.log(z / w) + (ix - m) * Math.log(w * p / (x1 * q)) +
                                            (13860.0 - (462.0 - (132.0 - (99.0 - 140.0 / f2) / f2) / f2) / f2) / f1 /
                                                            166320.0 +
                                            (13860.0 - (462.0 - (132.0 - (99.0 - 140.0 / z2) / z2) / z2) / z2) / z / 166320.0 +
                                            (13860.0 - (462.0 - (132.0 - (99.0 - 140.0 / x2) / x2) / x2) / x2) / x1 / 166320.0 + (13860.0 - (462.0 - (132.0 - (99.0 - 140.0 / w2) / w2) / w2) / w2) /
                                                            w / 166320.) {
                                // goto finis;
                                break finis;
                            }
                        }
                    }
                }
            } while (false); // L_np_small:
            /*---------------------- np = n*p < 30 : ------------------------- */

            while (true) {
                ix = 0;
                f = qn;
                u = unifRand();
                while (true) {
                    if (u < f) {
                        // goto finis;
                        break finis;
                    }
                    if (ix > 110) {
                        break;
                    }
                    u -= f;
                    ix++;
                    f *= (g / ix - r);
                }
            }
        } while (false); // finis:

        if (psave > 0.5) {
            ix = n - ix;
        }
        return ix;
    }

    @Override
    protected void createCasts(CastBuilder casts) {
        casts.toDouble(0).toDouble(1).toDouble(2);
    }

    @Specialization
    protected Object rbinom(RAbstractDoubleVector n, RAbstractDoubleVector size, RAbstractDoubleVector prob,  //
                    @Cached("create()") NAProfile na, //
                    @Cached("create()") BranchProfile nanProfile, //
                    @Cached("create()") VectorLengthProfile sizeProfile, //
                    @Cached("create()") VectorLengthProfile probProfile) {
        int length = n.getLength();
        RNode.reportWork(this, length);
        if (length == 1) {
            double l = n.getDataAt(0);
            if (Double.isNaN(l) || l < 0 || l > Integer.MAX_VALUE) {
                throw RError.error(RError.SHOW_CALLER, Message.INVALID_UNNAMED_ARGUMENTS);
            }
            length = (int) l;
        }
        int sizeLength = sizeProfile.profile(size.getLength());
        int probLength = probProfile.profile(prob.getLength());

        double[] result = new double[length];
        boolean complete = true;
        boolean nans = false;
        for (int i = 0; i < length; i++) {
            double value = rbinom(size.getDataAt(i % sizeLength), prob.getDataAt(i % probLength), nanProfile);
            if (na.isNA(value)) {
                complete = false;
            } else if (Double.isNaN(value)) {
                nans = true;
            }
            result[i] = value;
        }
        if (nans) {
            RError.warning(RError.SHOW_CALLER, RError.Message.NAN_PRODUCED);
        }
        return RDataFactory.createDoubleVector(result, complete);
    }
}

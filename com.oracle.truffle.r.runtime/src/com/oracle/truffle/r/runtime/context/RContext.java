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
package com.oracle.truffle.r.runtime.context;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.ExecutionContext;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.InvalidAssumptionException;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.vm.PolyglotEngine;
import com.oracle.truffle.r.runtime.ExitException;
import com.oracle.truffle.r.runtime.FastROptions;
import com.oracle.truffle.r.runtime.LazyDBCache;
import com.oracle.truffle.r.runtime.PrimitiveMethodsInfo;
import com.oracle.truffle.r.runtime.RCmdOptions;
import com.oracle.truffle.r.runtime.RCmdOptions.Client;
import com.oracle.truffle.r.runtime.REnvVars;
import com.oracle.truffle.r.runtime.RError;
import com.oracle.truffle.r.runtime.RErrorHandling;
import com.oracle.truffle.r.runtime.RInternalCode.ContextStateImpl;
import com.oracle.truffle.r.runtime.builtins.RBuiltinDescriptor;
import com.oracle.truffle.r.runtime.builtins.RBuiltinKind;
import com.oracle.truffle.r.runtime.builtins.RBuiltinLookup;
import com.oracle.truffle.r.runtime.RInternalError;
import com.oracle.truffle.r.runtime.ROptions;
import com.oracle.truffle.r.runtime.RProfile;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.RRuntimeASTAccess;
import com.oracle.truffle.r.runtime.RSerialize;
import com.oracle.truffle.r.runtime.RStartParams;
import com.oracle.truffle.r.runtime.RSource;
import com.oracle.truffle.r.runtime.RVisibility;
import com.oracle.truffle.r.runtime.Utils;
import com.oracle.truffle.r.runtime.conn.ConnectionSupport;
import com.oracle.truffle.r.runtime.conn.StdConnections;
import com.oracle.truffle.r.runtime.context.Engine.ParseException;
import com.oracle.truffle.r.runtime.data.RDataFactory;
import com.oracle.truffle.r.runtime.data.RFunction;
import com.oracle.truffle.r.runtime.data.RList;
import com.oracle.truffle.r.runtime.data.RStringVector;
import com.oracle.truffle.r.runtime.env.REnvironment;
import com.oracle.truffle.r.runtime.ffi.RFFIContextStateFactory;
import com.oracle.truffle.r.runtime.ffi.RFFIFactory;
import com.oracle.truffle.r.runtime.instrument.InstrumentationState;
import com.oracle.truffle.r.runtime.nodes.RCodeBuilder;
import com.oracle.truffle.r.runtime.nodes.RSyntaxNode;
import com.oracle.truffle.r.runtime.rng.RRNG;

/**
 * Encapsulates the runtime state ("context") of an R session. All access to that state from the
 * implementation <b>must</b> go through this class. There can be multiple instances
 * (multiple-tenancy) active within a single process/Java-VM.
 *
 * Contexts are created during construction of the {@link PolyglotEngine} instance. In case a
 * context needs to be configured, the PolyglotEngine needs to be created via the
 * {@code RContextFactory} class, which takes a {@link ContextInfo} object for configuration.
 *
 * The context provides a so-called {@link Engine} (accessed via {@link #getEngine()}), which
 * provides basic parsing and execution functionality .
 *
 * Context-specific state for implementation classes is managed by this class (or the associated
 * engine) and accessed through the {@code stateXyz} fields.
 *
 * Contexts can be destroyed
 */
public final class RContext extends ExecutionContext implements TruffleObject {

    public static final int CONSOLE_WIDTH = 80;

    public enum ContextKind {
        /**
         * Essentially a clean restart, modulo the basic VM-wide initialization. which does include,
         * for example, reading the external environment variables. I.e., it is not a goal to create
         * a multi-user environment with different external inputs. This kind of context can be used
         * in a parallel computation. The initial context is always of this kind. The intent is that
         * all mutable state is localized to the context, which may not be completely achievable.
         * For example, shared native libraries are assumed to contain no mutable state and be
         * re-entrant.
         */
        SHARE_NOTHING,

        /**
         * Shares the set of loaded packages of the parent context at the time the context is
         * created. Only useful when there is a priori knowledge on the evaluation that the context
         * will be used for. Cannot safely be used for parallel context evaluation. Must be created
         * as a child of an existing parent context of type {@link #SHARE_NOTHING} or
         * {@link #SHARE_PARENT_RO} and only one such child is allowed. (Strictly speaking the
         * invariant should be only one active child, but the implementation enforces it at creation
         * time). Evidently any changes made to the shared environment, e.g., loading a package,
         * affect the parent.
         */
        SHARE_PARENT_RW,

        /**
         * Intermediate between {@link #SHARE_NOTHING} and {@link #SHARE_PARENT_RW}, this is similar
         * to the standard shared code/copied data model provided by operating systems, although the
         * code/data distinction isn't completely applicable to a language like R. Unlike
         * {@link #SHARE_NOTHING}, where the ASTs for the functions in the default packages are
         * distinct copies in each context, in this kind of context, they are shared. Strictly
         * speaking, the bindings of R functions are shared, and this is achieved by creating a
         * shallow copy of the environments associated with the default packages of the parent
         * context at the time the context is created.
         */
        SHARE_PARENT_RO;

        public static final ContextKind[] VALUES = values();
    }

    /**
     * Tagging interface denoting a (usually inner-) class that carries the context-specific state
     * for a class that has context-specific state. The class specific state must implement this
     * interface.
     */
    public interface ContextState {
        /**
         * Called in response to the {@link RContext#destroy} method. Provides a hook for finalizing
         * any state before the context is destroyed.
         */
        @SuppressWarnings("unused")
        default void beforeDestroy(RContext context) {
            // default empty implementation
        }
    }

    /**
     * A thread that is explicitly associated with a context for efficient lookup.
     */
    public abstract static class ContextThread extends Thread {
        private RContext context;

        public ContextThread(RContext context) {
            this.context = context;
        }

        public void setContext(RContext context) {
            this.context = context;
        }
    }

    /**
     * A thread for performing an evaluation (used by {@code .fastr} builtins).
     */
    public static class EvalThread extends ContextThread {

        private static final Source GET_CONTEXT = RSource.fromTextInternal("invisible(.fastr.context.get())", RSource.Internal.GET_CONTEXT);

        private final Source source;
        private final ContextInfo info;
        private RList evalResult;

        public static final Map<Integer, Thread> threads = new ConcurrentHashMap<>();

        public EvalThread(ContextInfo info, Source source) {
            super(null);
            this.info = info;
            this.source = source;
            threads.put(info.getId(), this);
        }

        @Override
        public void run() {
            PolyglotEngine vm = info.createVM();
            try {
                setContext(vm.eval(GET_CONTEXT).as(RContext.class));
            } catch (Exception e1) {
                throw new RInternalError(e1, "error while initializing eval thread");
            }
            try {
                try {
                    PolyglotEngine.Value resultValue = vm.eval(source);
                    evalResult = createEvalResult(resultValue);
                } catch (ParseException e) {
                    e.report(info.getConsoleHandler());
                    evalResult = createErrorResult(e.getMessage());
                } catch (IOException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof ExitException) {
                        // termination, treat this as "success"
                        ExitException exitException = (ExitException) cause;
                        evalResult = RDataFactory.createList(new Object[]{exitException.getStatus()});
                    } else {
                        // some internal error
                        RInternalError.reportErrorAndConsoleLog(cause, info.getConsoleHandler(), info.getId());
                        evalResult = createErrorResult(cause.getClass().getSimpleName());
                    }
                }
            } finally {
                vm.dispose();
                threads.remove(info.getId());
            }
        }

        /**
         * The result is an {@link RList} contain the value, plus an "error" attribute if the
         * evaluation resulted in an error.
         */
        public static RList createEvalResult(PolyglotEngine.Value resultValue) throws IOException {
            Object result = resultValue.get();
            Object listResult = result;
            String error = null;
            if (result == null) {
                // this means an error occurred and there is no result
                listResult = RRuntime.LOGICAL_NA;
                error = "R error";
            } else if (result instanceof TruffleObject) {
                listResult = resultValue.as(Object.class);
            } else {
                listResult = result;
            }
            RList list = RDataFactory.createList(new Object[]{listResult});
            if (error != null) {
                list.setAttr("error", error);
            }
            return list;
        }

        public static RList createErrorResult(String errorMsg) {
            RList list = RDataFactory.createList(new Object[]{RRuntime.LOGICAL_NA});
            list.setAttr("error", errorMsg);
            return list;

        }

        public RList getEvalResult() {
            return evalResult;
        }
    }

    private final ContextInfo info;
    private final Engine engine;

    /**
     * Denote whether the result of an expression should be printed in the shell or not. This value
     * will be modified by many operations like builtins, block statements, etc.
     */
    private boolean resultVisible = false;

    /**
     * A context-specific value that is checked in {@code HiddenInternalFunctions} to avoid an error
     * report on a {@code SUBSTITUTE} builtin. Not worth promoting to a {@link ContextState}.
     */
    private boolean loadingBase;

    /**
     * At most one shared child.
     */
    private RContext sharedChild;

    /**
     * Typically there is a 1-1 relationship between an {@link RContext} and the thread that is
     * performing the evaluation, so we can store the {@link RContext} in a {@link ThreadLocal}.
     *
     * When a context is first created no threads are attached, to allow contexts to be used as
     * values in the experimental {@code fastr.createcontext} function. Additional threads can be
     * added by the {@link #attachThread} method.
     */
    public static final ThreadLocal<RContext> threadLocalContext = new ThreadLocal<>();

    /**
     * Used by the MethodListDispatch class.
     */
    private boolean methodTableDispatchOn = false;
    private boolean allowPrimitiveMethods = true;
    private HashMap<String, RStringVector> s4ExtendsTable = new HashMap<>();

    private boolean nullS4Object = false;

    private boolean active;

    private PrimitiveMethodsInfo primitiveMethodsInfo;

    /**
     * A (hopefully) temporary workaround to ignore the setting of {@link #resultVisible} for
     * benchmarks. Set across all contexts.
     */
    @CompilationFinal private static boolean ignoreVisibility;

    /**
     * Set to {@code true} when in embedded mode to allow other parts of the system to determine
     * whether embedded mode is in effect, <b>before</b> the initial context is created.
     */
    private static boolean embedded;

    /*
     * Workarounds to finesse project circularities between runtime/nodes.
     */
    @CompilationFinal private static RCodeBuilder<RSyntaxNode> astBuilder;
    @CompilationFinal private static RRuntimeASTAccess runtimeASTAccess;
    @CompilationFinal private static RBuiltinLookup builtinLookup;
    @CompilationFinal private static RForeignAccessFactory foreignAccessFactory;
    @CompilationFinal private static boolean initialContextInitialized;

    public static boolean isInitialContextInitialized() {
        return initialContextInitialized;
    }

    /**
     * Initialize VM-wide static values.
     */
    public static void initialize(RCodeBuilder<RSyntaxNode> rAstBuilder, RRuntimeASTAccess rRuntimeASTAccess, RBuiltinLookup rBuiltinLookup, RForeignAccessFactory rForeignAccessFactory) {
        RContext.astBuilder = rAstBuilder;
        RContext.runtimeASTAccess = rRuntimeASTAccess;
        RContext.builtinLookup = rBuiltinLookup;
        RContext.foreignAccessFactory = rForeignAccessFactory;
    }

    /**
     * Associates this {@link RContext} with the current thread.
     */
    private void attachThread() {
        Thread current = Thread.currentThread();
        if (current instanceof ContextThread) {
            ((ContextThread) current).setContext(this);
        } else {
            threadLocalContext.set(this);
        }
    }

    private static final Assumption singleContextAssumption = Truffle.getRuntime().createAssumption("single RContext");
    @CompilationFinal private static RContext singleContext;

    private final Env env;
    private final HashMap<String, TruffleObject> exportedSymbols = new HashMap<>();
    private final boolean initial;
    /**
     * State that is used to support interposing on loadNamespace() for overrides.
     */
    @CompilationFinal private String nameSpaceName;

    /**
     * The set of classes for which the context manages context-specific state, and their state. We
     * could do this more dynamically with a registration process, perhaps driven by an annotation
     * processor, but the set is relatively small, so we just enumerate them here.
     */
    public REnvVars stateREnvVars;
    public RProfile stateRProfile;
    public ROptions.ContextStateImpl stateROptions;
    public final REnvironment.ContextStateImpl stateREnvironment;
    public final RErrorHandling.ContextStateImpl stateRErrorHandling;
    public final ConnectionSupport.ContextStateImpl stateRConnection;
    public final StdConnections.ContextStateImpl stateStdConnections;
    public final RRNG.ContextStateImpl stateRNG;
    public final ContextState stateRFFI;
    public final RSerialize.ContextStateImpl stateRSerialize;
    public final LazyDBCache.ContextStateImpl stateLazyDBCache;
    public final InstrumentationState stateInstrumentation;
    public final ContextStateImpl stateInternalCode;

    private ContextState[] contextStates() {
        return new ContextState[]{stateREnvVars, stateRProfile, stateROptions, stateREnvironment, stateRErrorHandling, stateRConnection, stateStdConnections, stateRNG, stateRFFI, stateRSerialize,
                        stateLazyDBCache, stateInstrumentation};
    }

    public static void setEmbedded() {
        embedded = true;
    }

    public static boolean isEmbedded() {
        return embedded;
    }

    private RContext(Env env, Instrumenter instrumenter, boolean isInitial) {
        ContextInfo initialInfo = (ContextInfo) env.importSymbol(ContextInfo.GLOBAL_SYMBOL);
        if (initialInfo == null) {
            /*
             * This implies that FastR is being invoked initially from another Truffle language and
             * not via RCommand/RscriptCommand.
             */
            this.info = ContextInfo.create(new RStartParams(RCmdOptions.parseArguments(Client.R, new String[0], false), false), ContextKind.SHARE_NOTHING, null,
                            new DefaultConsoleHandler(env.in(), env.out()));
        } else {
            this.info = initialInfo;
        }
        this.initial = isInitial;

        // this must happen before engine activation in the code below
        if (info.getKind() == ContextKind.SHARE_NOTHING) {
            if (info.getParent() == null) {
                this.primitiveMethodsInfo = new PrimitiveMethodsInfo();
            } else {
                // share nothing contexts need their own copy of the primitive methods meta-data as
                // they can run (and update this meta data) concurrently with the parent;
                // alternative would be to copy on-write but we would need some kind of locking
                // machinery to avoid races
                assert info.getParent().getPrimitiveMethodsInfo() != null;
                this.primitiveMethodsInfo = info.getParent().getPrimitiveMethodsInfo().duplicate();
            }
        }

        this.env = env;
        if (info.getConsoleHandler() == null) {
            throw Utils.rSuicide("no console handler set");
        }

        if (singleContextAssumption.isValid()) {
            if (singleContext == null) {
                singleContext = this;
            } else {
                singleContext = null;
                singleContextAssumption.invalidate();
            }
        }
        engine = RContext.getRRuntimeASTAccess().createEngine(this);

        /*
         * Activate the context by attaching the current thread and initializing the {@link
         * ContextState} objects. Note that we attach the thread before creating the new context
         * state. This means that code that accesses the state through this interface will receive a
         * {@code null} value. Access to the parent state is available through the {@link RContext}
         * argument passed to the newContext methods. It might be better to attach the thread after
         * state creation but it is a finely balanced decision and risks incorrectly accessing the
         * parent state.
         */
        assert !active;
        active = true;
        attachThread();
        if (!embedded) {
            doEnvOptionsProfileInitialization();
        }
        stateREnvironment = REnvironment.ContextStateImpl.newContext(this);
        stateRErrorHandling = RErrorHandling.ContextStateImpl.newContext(this);
        stateRConnection = ConnectionSupport.ContextStateImpl.newContext(this);
        stateStdConnections = StdConnections.ContextStateImpl.newContext(this);
        stateRNG = RRNG.ContextStateImpl.newContext(this);
        stateRFFI = RFFIContextStateFactory.newContext(this);
        stateRSerialize = RSerialize.ContextStateImpl.newContext(this);
        stateLazyDBCache = LazyDBCache.ContextStateImpl.newContext(this);
        stateInstrumentation = InstrumentationState.newContext(this, instrumenter);
        stateInternalCode = ContextStateImpl.newContext(this);

        if (!embedded) {
            validateContextStates();
            engine.activate(stateREnvironment);
        }

        if (info.getKind() == ContextKind.SHARE_PARENT_RW) {
            if (info.getParent().sharedChild != null) {
                throw RError.error(RError.SHOW_CALLER2, RError.Message.GENERIC, "can't have multiple active SHARED_PARENT_RW contexts");
            }
            info.getParent().sharedChild = this;
            // this one must be shared between contexts - otherwise testing contexts do not know
            // that methods package is loaded
            this.methodTableDispatchOn = info.getParent().methodTableDispatchOn;
        }
        if (isInitial && !embedded) {
            RFFIFactory.getRFFI().getCallRFFI().setInteractive(isInteractive());
            initialContextInitialized = true;
        }
    }

    /**
     * Factored out for embedded setup, where this initialization may be customized after the
     * context is created but before VM really starts execution.
     */
    private void doEnvOptionsProfileInitialization() {
        stateREnvVars = REnvVars.newContext(this);
        stateROptions = ROptions.ContextStateImpl.newContext(this, stateREnvVars);
        stateRProfile = RProfile.newContext(this, stateREnvVars);
    }

    public void completeEmbeddedInitialization() {
        doEnvOptionsProfileInitialization();
        validateContextStates();
        engine.activate(stateREnvironment);
        stateROptions.updateDotOptions();
        initialContextInitialized = true;
    }

    private void validateContextStates() {
        for (ContextState state : contextStates()) {
            assert state != null;
        }
    }

    /**
     * Create a context with the given configuration.
     *
     * @param isInitial {@code true} iff this is the initial context
     */
    public static RContext create(Env env, Instrumenter instrumenter, boolean isInitial) {
        return new RContext(env, instrumenter, isInitial);
    }

    /**
     * Destroy this context.
     */
    public void destroy() {
        for (ContextState state : contextStates()) {
            state.beforeDestroy(this);
        }
        if (info.getKind() == ContextKind.SHARE_PARENT_RW) {
            info.getParent().sharedChild = null;
        }
        if (info.getParent() == null) {
            threadLocalContext.set(null);
        } else {
            threadLocalContext.set(info.getParent());
        }
    }

    public RContext getParent() {
        return info.getParent();
    }

    public Env getEnv() {
        return env;
    }

    public InstrumentationState getInstrumentationState() {
        return stateInstrumentation;
    }

    public ContextKind getKind() {
        return info.getKind();
    }

    @TruffleBoundary
    private static RContext getInstanceInternal() {
        RContext result = threadLocalContext.get();
        assert result != null;
        assert result.active;
        return result;
    }

    public static RContext getInstance() {
        RContext context = singleContext;
        if (context != null) {
            try {
                singleContextAssumption.check();
                return context;
            } catch (InvalidAssumptionException e) {
                // fallback to slow case
            }
        }

        Thread current = Thread.currentThread();
        if (current instanceof ContextThread) {
            context = ((ContextThread) current).context;
            assert context != null;
            return context;
        } else {
            return getInstanceInternal();
        }
    }

    /**
     * Access to the engine, when an {@link RContext} object is available, and/or when {@code this}
     * context is not active.
     */
    public Engine getThisEngine() {
        return engine;
    }

    public boolean isVisible() {
        return resultVisible;
    }

    public void setVisible(boolean v) {
        if (!FastROptions.IgnoreVisibility.getBooleanValue()) {
            /*
             * Prevent printing the dummy expression used to force creation of initial context
             */
            if (initialContextInitialized || !embedded) {
                resultVisible = v;
            }
        }
    }

    public void setVisible(RVisibility visibility) {
        if (visibility == RVisibility.ON) {
            setVisible(true);
        } else if (visibility == RVisibility.OFF) {
            setVisible(false);
        }
    }

    public boolean isMethodTableDispatchOn() {
        return methodTableDispatchOn;
    }

    public void setMethodTableDispatchOn(boolean on) {
        methodTableDispatchOn = on;
    }

    public boolean isNullS4Object() {
        return nullS4Object;
    }

    public void setNullS4Object(boolean on) {
        nullS4Object = on;
    }

    public boolean allowPrimitiveMethods() {
        return allowPrimitiveMethods;
    }

    public void setAllowPrimitiveMethods(boolean on) {
        allowPrimitiveMethods = on;
    }

    public RStringVector getS4Extends(String key) {
        return s4ExtendsTable.get(key);
    }

    public void putS4Extends(String key, RStringVector value) {
        s4ExtendsTable.put(key, value);
    }

    public PrimitiveMethodsInfo getPrimitiveMethodsInfo() {
        if (primitiveMethodsInfo == null) {
            // shared contexts do not run concurrently with their parent and re-use primitive
            // methods information
            assert info.getKind() != ContextKind.SHARE_NOTHING;
            assert info.getParent() != null;
            return info.getParent().getPrimitiveMethodsInfo();
        } else {
            return primitiveMethodsInfo;
        }
    }

    public boolean isInteractive() {
        return info.getConsoleHandler().isInteractive();
    }

    public ConsoleHandler getConsoleHandler() {
        return info.getConsoleHandler();
    }

    /**
     * This is a static property of the implementation and not context-specific.
     */
    public static RCodeBuilder<RSyntaxNode> getASTBuilder() {
        return astBuilder;
    }

    /**
     * This is a static property of the implementation and not context-specific.
     */
    public static RRuntimeASTAccess getRRuntimeASTAccess() {
        return runtimeASTAccess;
    }

    /**
     * Is {@code name} a builtin function (but not a {@link RBuiltinKind#INTERNAL}?
     */
    public static boolean isPrimitiveBuiltin(String name) {
        return builtinLookup.isPrimitiveBuiltin(name);
    }

    /**
     * Return the {@link RFunction} for the builtin {@code name}.
     */
    @TruffleBoundary
    public static RFunction lookupBuiltin(String name) {
        return builtinLookup.lookupBuiltin(name);
    }

    /**
     * Returns the descriptor for the builtin with the given name. This does not cause an RFunction
     * to be created.
     */
    public static RBuiltinDescriptor lookupBuiltinDescriptor(String name) {
        return builtinLookup.lookupBuiltinDescriptor(name);
    }

    public static RForeignAccessFactory getRForeignAccessFactory() {
        return foreignAccessFactory;
    }

    public RStartParams getStartParams() {
        return info.getStartParams();
    }

    @Override
    public String toString() {
        return "context: " + info.getId();
    }

    /*
     * static functions necessary in code where the context is only implicit in the thread(s)
     * running an evaluation
     */

    public static Engine getEngine() {
        return RContext.getInstance().engine;
    }

    public PolyglotEngine getVM() {
        return info.getVM();
    }

    public boolean isInitial() {
        return initial;
    }

    public void setLoadingBase(boolean b) {
        loadingBase = b;
    }

    public boolean getLoadingBase() {
        return loadingBase;
    }

    public Map<String, TruffleObject> getExportedSymbols() {
        return exportedSymbols;
    }

    public TimeZone getSystemTimeZone() {
        return info.getSystemTimeZone();
    }

    public String getNamespaceName() {
        return nameSpaceName;
    }

    public void setNamespaceName(String name) {
        nameSpaceName = name;
    }

    @Override
    public ForeignAccess getForeignAccess() {
        throw new IllegalStateException("cannot access " + RContext.class.getSimpleName() + " via Truffle");
    }

    public static Closeable withinContext(RContext context) {
        RContext oldContext = RContext.threadLocalContext.get();
        RContext.threadLocalContext.set(context);
        return new Closeable() {
            @Override
            public void close() {
                RContext.threadLocalContext.set(oldContext);
            }
        };
    }
}

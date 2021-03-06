/*
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.runtime.core;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.PackedFrame;
import com.oracle.truffle.api.utilities.CyclicAssumption;
import org.jruby.runtime.Visibility;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.control.RaiseException;
import org.jruby.truffle.runtime.lookup.LookupFork;
import org.jruby.truffle.runtime.lookup.LookupNode;
import org.jruby.truffle.runtime.lookup.LookupTerminal;
import org.jruby.truffle.runtime.methods.RubyMethod;

import java.util.*;

/**
 * Represents the Ruby {@code Module} class.
 */
public class RubyModule extends RubyObject implements LookupNode {

    /**
     * The class from which we create the object that is {@code Module}. A subclass of
     * {@link RubyClass} so that we can override {@link #newInstance} and allocate a
     * {@link RubyModule} rather than a normal {@link RubyBasicObject}.
     */
    public static class RubyModuleClass extends RubyClass {

        public RubyModuleClass(RubyContext context) {
            super(context, null, null, null, "Module");
        }

        @Override
        public RubyBasicObject newInstance() {
            return new RubyModule(this, null, "(unnamed module)");
        }

    }

    /**
     * The slot within a module definition method frame where we store the implicit state that is
     * the current visibility for new methods.
     */
    public static final Object VISIBILITY_FRAME_SLOT_ID = new Object();

    /**
     * The slot within a module definition method frame where we store the implicit state that is
     * the flag for whether or not new methods will be module methods (functions is the term).
     */
    public static final Object MODULE_FUNCTION_FLAG_FRAME_SLOT_ID = new Object();

    // The context is stored here - objects can obtain it via their class (which is a module)
    private final RubyContext context;

    /*
     * The module in which this module was defined. By analogy, if superclass is the dynamic scope,
     * the parent module is the lexical scope.
     */
    private final RubyModule parentModule;

    /*
     * The first thing to lookup names in. Not always the class, as we also have singleton classes,
     * included modules etc.
     */
    private LookupNode lookupParent = LookupTerminal.INSTANCE;

    private final String name;
    private final Map<String, RubyMethod> methods = new HashMap<>();
    private final Map<String, RubyConstant> constants = new HashMap<>();
    private final Map<String, Object> classVariables = new HashMap<>();

    private final CyclicAssumption unmodifiedAssumption;

    /**
     * Keep track of other modules that depend on the configuration of this module in some way. The
     * include subclasses and modules that include this module.
     */
    private final Set<RubyModule> dependents = Collections.newSetFromMap(new WeakHashMap<RubyModule, Boolean>());

    public RubyModule(RubyClass rubyClass, RubyModule parentModule, String name) {
        this(rubyClass.getContext(), rubyClass, parentModule, name);
    }

    public RubyModule(RubyContext context, RubyClass rubyClass, RubyModule parentModule, String name) {
        super(rubyClass);

        this.context = context;
        this.parentModule = parentModule;
        this.name = name;

        unmodifiedAssumption = new CyclicAssumption(name + " is unmodified");

        /*
         * Modules always go into the object space manager. Manually allocate an objectID, because
         * the lazy mechanism uses the Ruby class of the object, which may not be set yet during
         * bootstrap.
         */

        objectID = context.getNextObjectID();
        context.getObjectSpaceManager().add(this);
    }

    public RubyModule getParentModule() {
        return parentModule;
    }

    public void include(RubyModule module) {
        checkFrozen();

        lookupParent = new LookupFork(module, lookupParent);
        newVersion();
        module.addDependent(this);
    }

    /**
     * Set the value of a constant, possibly redefining it.
     */
    public void setConstant(String constantName, Object value) {
        assert RubyContext.shouldObjectBeVisible(value);
        checkFrozen();
        getConstants().put(constantName, new RubyConstant(value, false));
        newVersion();
        // TODO(CS): warn when redefining a constant
    }

    public void removeConstant(String data) {
        checkFrozen();
        getConstants().remove(data);
        newVersion();
    }

    public void setClassVariable(String variableName, Object value) {
        assert RubyContext.shouldObjectBeVisible(value);

        checkFrozen();

        if (!setClassVariableIfAlreadySet(variableName, value)) {
            classVariables.put(variableName, value);
        }
    }

    public boolean setClassVariableIfAlreadySet(String variableName, Object value) {
        assert RubyContext.shouldObjectBeVisible(value);

        checkFrozen();

        if (lookupParent.setClassVariableIfAlreadySet(variableName, value)) {
            return true;
        }

        if (classVariables.containsKey(variableName)) {
            classVariables.put(variableName, value);
            return true;
        }

        return false;
    }

    public void removeClassVariable(String variableName) {
        checkFrozen();

        classVariables.remove(variableName);
    }

    public void setModuleConstant(String constantName, Object value) {
        checkFrozen();

        setConstant(constantName, value);
        getSingletonClass().setConstant(constantName, value);
    }

    public void addMethod(RubyMethod method) {
        checkFrozen();
        getMethods().put(method.getName(), method);
        newVersion();
    }

    /**
     * Remove a method from this module.
     */
    public void removeMethod(String methodName) {
        checkFrozen();

        getMethods().remove(methodName);
        newVersion();
    }

    public void undefMethod(String methodName) {
        undefMethod(lookupMethod(methodName));
    }

    public void undefMethod(RubyMethod method) {
        addMethod(method.undefined());
    }

    /**
     * Alias a method.
     */
    public void alias(String newName, String oldName) {
        final RubyMethod method = lookupMethod(oldName);

        if (method == null) {
            CompilerDirectives.transferToInterpreter();
            throw new RaiseException(getRubyClass().getContext().getCoreLibrary().noMethodError(oldName, toString()));
        }

        addMethod(method.withNewName(newName));
    }

    @Override
    public RubyConstant lookupConstant(String constantName) {
        RubyConstant value;

        // Look in this module

        value = getConstants().get(constantName);

        if (value instanceof RubyConstant) {
            return ((RubyConstant) value);
        }

        // Look in the parent module

        if (parentModule != null) {
            value = parentModule.lookupConstant(constantName);

            if (value != null) {
                return value;
            }
        }

        // Look in the lookup parent

        return lookupParent.lookupConstant(constantName);
    }

    public void setConstantPrivate(RubySymbol constant) {
        RubyConstant rubyConstant = lookupConstant(constant.toString());

        if (rubyConstant != null) {
            rubyConstant.isPrivate = true;
        } else {
            // raises an exception
        }
    }

    @Override
    public Object lookupClassVariable(String variableName) {
        // Look in this module

        final Object value = classVariables.get(variableName);

        if (value != null) {
            return value;
        }

        // Look in the parent

        return lookupParent.lookupClassVariable(variableName);
    }

    public Set<String> getClassVariables() {
        final Set<String> classVariablesSet = new HashSet<>();

        classVariablesSet.addAll(classVariables.keySet());
        classVariablesSet.addAll(lookupParent.getClassVariables());

        return classVariablesSet;
    }

    @Override
    public RubyMethod lookupMethod(String methodName) {
        // Look in this module

        final RubyMethod method = getMethods().get(methodName);

        if (method != null) {
            return method;
        }

        // Look in the parent

        return lookupParent.lookupMethod(methodName);
    }

    public void appendFeatures(RubyModule other) {
        // TODO(CS): check only run once

        for (Map.Entry<String, RubyConstant> constantEntry : getConstants().entrySet()) {
            final String constantName = constantEntry.getKey();
            final Object constantValue = constantEntry.getValue().value;
            other.setModuleConstant(constantName, constantValue);
        }

        for (Map.Entry<String, RubyMethod> methodEntry : getMethods().entrySet()) {
            final String methodName = methodEntry.getKey();
            final RubyMethod method = methodEntry.getValue();
            other.addMethod(method.withNewName(methodName));
        }
    }

    public RubyContext getContext() {
        return context;
    }

    public String getName() {
        return name;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }

    public void newVersion() {
        unmodifiedAssumption.invalidate();

        // Make dependents new versions

        for (RubyModule dependent : dependents) {
            dependent.newVersion();
        }
    }

    public void addDependent(RubyModule dependent) {
        dependents.add(dependent);
    }

    public Assumption getUnmodifiedAssumption() {
        return unmodifiedAssumption.getAssumption();
    }

    public void getMethods(Map<String, RubyMethod> foundMethods) {
        lookupParent.getMethods(foundMethods);

        for (RubyMethod method : methods.values()) {
            foundMethods.put(method.getName(), method);
        }
    }

    public static void setCurrentVisibility(Frame frame, Visibility visibility) {
        frame.setObject(frame.getFrameDescriptor().findFrameSlot(VISIBILITY_FRAME_SLOT_ID), visibility);
    }

    public void visibilityMethod(PackedFrame frame, Object[] arguments, Visibility visibility) {
        if (arguments.length == 0) {
            setCurrentVisibility(frame.unpack(), visibility);
        } else {
            for (Object arg : arguments) {
                final RubyMethod method = lookupMethod(arg.toString());

                if (method == null) {
                    throw new RuntimeException("Couldn't find method " + arg.toString());
                }

                /*
                 * If the method was already defined in this class, that's fine {@link addMethod}
                 * will overwrite it, otherwise we do actually want to add a copy of the method with
                 * a different visibility to this module.
                 */

                addMethod(method.withNewVisibility(visibility));
            }
        }
    }

    public List<RubyMethod> getDeclaredMethods() {
        return new ArrayList<>(getMethods().values());
    }

    public void moduleEval(String source) {
        getRubyClass().getContext().eval(source, this);
    }

    public Map<String, RubyConstant> getConstants() {
        return constants;
    }

    public Map<String, RubyMethod> getMethods() {
        return methods;
    }

    public static class RubyConstant {
        public final Object value;
        public boolean isPrivate;

        public RubyConstant(Object value, boolean isPrivate) {
            this.value = value;
            this.isPrivate = isPrivate;
        }

    }

}

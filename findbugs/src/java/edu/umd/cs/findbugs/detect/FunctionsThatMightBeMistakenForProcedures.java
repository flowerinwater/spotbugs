/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2004-2006 University of Maryland
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.detect;

import java.util.HashSet;

import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.generic.Type;

import edu.umd.cs.findbugs.BugAccumulator;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.FieldAnnotation;
import edu.umd.cs.findbugs.MethodAnnotation;
import edu.umd.cs.findbugs.NonReportingDetector;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.OpcodeStack.Item;
import edu.umd.cs.findbugs.SystemProperties;
import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.ba.SignatureParser;
import edu.umd.cs.findbugs.ba.XClass;
import edu.umd.cs.findbugs.ba.XFactory;
import edu.umd.cs.findbugs.ba.XMethod;
import edu.umd.cs.findbugs.ba.generic.GenericObjectType;
import edu.umd.cs.findbugs.ba.generic.GenericSignatureParser;
import edu.umd.cs.findbugs.ba.generic.GenericUtilities;
import edu.umd.cs.findbugs.bcel.OpcodeStackDetector;
import edu.umd.cs.findbugs.internalAnnotations.SlashedClassName;
import edu.umd.cs.findbugs.util.ClassName;

public class FunctionsThatMightBeMistakenForProcedures extends OpcodeStackDetector implements NonReportingDetector {

    final BugReporter bugReporter;
    
    final static boolean REPORT_INFERRED_METHODS = SystemProperties.getBoolean("mrc.inferred.report");

    public FunctionsThatMightBeMistakenForProcedures(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
        setVisitMethodsInCallOrder(true);
    }

    public void visit(JavaClass obj) {

    }

    @Override
    public void visitAfter(JavaClass obj) {
        okToIgnore.clear();
        doNotIgnore.clear();
        doNotIgnoreHigh.clear();
        methodsSeen.clear();

    }

    HashSet<XMethod> okToIgnore = new HashSet<XMethod>();

    HashSet<XMethod> methodsSeen = new HashSet<XMethod>();

    HashSet<XMethod> doNotIgnore = new HashSet<XMethod>();

    HashSet<XMethod> doNotIgnoreHigh = new HashSet<XMethod>();

    int returnSelf, returnOther, returnNew, returnUnknown;

    int updates;
    
    BugInstance inferredMethod;

    @Override
    public void visit(Code code) {

        methodsSeen.add(getXMethod());
        String signature = getMethodSig();
        SignatureParser parser = new SignatureParser(signature);

        String returnType = parser.getReturnTypeSignature();
        @SlashedClassName
        String r = ClassName.fromFieldSignature(returnType);
        if (r == null || !r.equals(getClassName()))
            return;
//        System.out.println("Checking " + getFullyQualifiedMethodName());
        boolean funky = false;
        for (int i = 0; i < parser.getNumParameters(); i++) {
            String p = ClassName.fromFieldSignature(parser.getParameter(i));
            if (getClassName().equals(p)) {
                funky = true;
            }

        }
        //
        XMethod m = getXMethod();
        String sourceSig = m.getSourceSignature();
        if (sourceSig != null) {
            GenericSignatureParser sig = new GenericSignatureParser(sourceSig);
            String genericReturnValue = sig.getReturnTypeSignature();
            Type t = GenericUtilities.getType(genericReturnValue);
            if (t instanceof GenericObjectType) {
                funky = true;
            }

            if (false) {
                XClass c = getXClass();
                String classSourceSig = c.getSourceSignature();
                if (!genericReturnValue.equals(classSourceSig))
                    return;
            }
        }

        // System.out.println(getFullyQualifiedMethodName());
        returnSelf = returnOther = updates = returnNew = returnUnknown = 0;
//        System.out.println(" investingating");

        if (REPORT_INFERRED_METHODS)
           inferredMethod = new BugInstance("TESTING", NORMAL_PRIORITY).addClassAndMethod(this);
        super.visit(code); // make callbacks to sawOpcode for all opcodes
//        System.out.printf("  %3d %3d %3d %3d%n", returnSelf, updates, returnOther, returnNew);

        if (returnSelf > 0 && returnOther == 0) {
            okToIgnore.add(m);
        } else if (funky && returnOther > returnNew) {
            okToIgnore.add(m);
        } else if (returnOther > 0 && returnOther >= returnSelf && returnNew > 0 && returnNew >= returnOther - 1) {

            int priority = HIGH_PRIORITY;
            if (returnSelf > 0 || updates > 0)
                priority++;
            if (returnUnknown > 0)
                priority++;
            if (returnNew > 0 && priority > NORMAL_PRIORITY)
                priority = NORMAL_PRIORITY;
            if (updates > 0)
                priority = LOW_PRIORITY;
            if (priority <= HIGH_PRIORITY)
                doNotIgnoreHigh.add(m);
            if (priority <= NORMAL_PRIORITY) {
//                System.out.printf("  adding %d %s%n", priority, MethodAnnotation.fromVisitedMethod(this).getSourceLines());
                doNotIgnore.add(m);
                if (!m.isStatic()) {
                    XFactory xFactory = AnalysisContext.currentXFactory();
                    xFactory.addFunctionThatMightBeMistakenForProcedures(getMethodDescriptor());
                }
            }

            if (REPORT_INFERRED_METHODS) {
                inferredMethod.setPriority(priority);
                inferredMethod.addString(
                        String.format("%3d %3d %5d %3d", returnOther, returnSelf, returnNew, updates));
                bugReporter.reportBug(inferredMethod);
            }
            inferredMethod = null;

        }

    }

    @Override
    public void sawOpcode(int seen) {
        if (getMethod().isStatic() && (seen == INVOKEVIRTUAL || seen == INVOKESPECIAL) ){
            String name = getNameConstantOperand();
            if (name.startsWith("set") || name.startsWith("update")) {
                Item invokedOn = stack.getItemMethodInvokedOn(this);
                if (invokedOn.isInitialParameter()
                        && invokedOn.getRegisterNumber() == 0)
                    updates++;
                if (REPORT_INFERRED_METHODS)
                    inferredMethod.addCalledMethod(this);
                    
            }
               
        }

        if (seen == ARETURN) {
            OpcodeStack.Item rv = stack.getStackItem(0);
            if (rv.isNull())
                return;
            if (rv.isInitialParameter())
                returnSelf++;
            else {
                XMethod xMethod = rv.getReturnValueOf();
                if (xMethod == null) {
                    returnSelf++;
                    return;
                } 
                if (REPORT_INFERRED_METHODS)
                    inferredMethod.addCalledMethod(xMethod);
                if (okToIgnore.contains(xMethod) || xMethod.getSignature().equals("()V") )
                    returnSelf++;
                else if (!xMethod.isAbstract() && xMethod.getClassDescriptor().equals(getClassDescriptor())) {
                    if (xMethod.getName().equals("<init>") || doNotIgnoreHigh.contains(xMethod)) {
                        returnOther++;
//                        System.out.println("  calls " + xMethod);
//                        System.out.println("  at " + MethodAnnotation.fromXMethod(xMethod).getSourceLines());
                        if (xMethod.getName().equals("<init>") || doNotIgnore.contains(xMethod))
                            returnNew++;
                    } else if (doNotIgnore.contains(xMethod)) {
                        returnOther++;
//                        System.out.println("  calls " + xMethod);
//                        System.out.println("  at " + MethodAnnotation.fromXMethod(xMethod).getSourceLines());

                    }
                } else {
                    returnUnknown++;
                }
            }
        } else if (seen == PUTFIELD) {
            OpcodeStack.Item rv = stack.getStackItem(1);
            if (rv.getRegisterNumber() == 0 && rv.isInitialParameter()) {
                if (REPORT_INFERRED_METHODS)
                    inferredMethod.addReferencedField(this);
               updates++;
            }
        }
    }

}

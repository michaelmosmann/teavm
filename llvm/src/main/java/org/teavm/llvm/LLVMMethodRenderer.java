/*
 *  Copyright 2016 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.llvm;

import static org.teavm.llvm.LLVMRenderingHelper.defaultValue;
import static org.teavm.llvm.LLVMRenderingHelper.getJavaTypeName;
import static org.teavm.llvm.LLVMRenderingHelper.mangleField;
import static org.teavm.llvm.LLVMRenderingHelper.mangleMethod;
import static org.teavm.llvm.LLVMRenderingHelper.methodType;
import static org.teavm.llvm.LLVMRenderingHelper.renderItemType;
import static org.teavm.llvm.LLVMRenderingHelper.renderType;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.IntObjectOpenHashMap;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.teavm.common.Graph;
import org.teavm.llvm.context.LayoutProvider;
import org.teavm.llvm.context.StringPool;
import org.teavm.llvm.context.TagRegistry;
import org.teavm.llvm.context.VirtualTableEntry;
import org.teavm.llvm.context.VirtualTableProvider;
import org.teavm.model.BasicBlock;
import org.teavm.model.BasicBlockReader;
import org.teavm.model.ClassReader;
import org.teavm.model.ClassReaderSource;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldReference;
import org.teavm.model.IncomingReader;
import org.teavm.model.Instruction;
import org.teavm.model.InstructionLocation;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHandle;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.PhiReader;
import org.teavm.model.Program;
import org.teavm.model.ProgramReader;
import org.teavm.model.RuntimeConstant;
import org.teavm.model.TryCatchJointReader;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.VariableReader;
import org.teavm.model.instructions.ArrayElementType;
import org.teavm.model.instructions.BinaryBranchingCondition;
import org.teavm.model.instructions.BinaryOperation;
import org.teavm.model.instructions.BranchingCondition;
import org.teavm.model.instructions.CastIntegerDirection;
import org.teavm.model.instructions.CloneArrayInstruction;
import org.teavm.model.instructions.ConstructArrayInstruction;
import org.teavm.model.instructions.ConstructInstruction;
import org.teavm.model.instructions.InitClassInstruction;
import org.teavm.model.instructions.InstructionReader;
import org.teavm.model.instructions.IntegerSubtype;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.NumericOperandType;
import org.teavm.model.instructions.SwitchTableEntryReader;
import org.teavm.model.util.DefinitionExtractor;
import org.teavm.model.util.LivenessAnalyzer;
import org.teavm.model.util.ProgramUtils;
import org.teavm.model.util.TypeInferer;
import org.teavm.model.util.VariableType;

class LLVMMethodRenderer {
    private Appendable appendable;
    private ClassReaderSource classSource;
    private StringPool stringPool;
    private LayoutProvider layoutProvider;
    private VirtualTableProvider vtableProvider;
    private TagRegistry tagRegistry;
    private List<String> emitted = new ArrayList<>();
    private int temporaryVariable;
    private TypeInferer typeInferer;
    private ValueType currentReturnType;
    private Map<VariableReader, List<VariableReader>> joints;
    private boolean expectingException;
    private int methodNameSize;
    private String methodNameVar;
    private BitSet callSiteLiveIns;
    private int stackFrameSize;
    private List<String> returnVariables = new ArrayList<>();
    private List<String> returnBlocks = new ArrayList<>();
    private String currentLlvmBlock;

    public LLVMMethodRenderer(Appendable appendable, ClassReaderSource classSource,
            StringPool stringPool, LayoutProvider layoutProvider,
            VirtualTableProvider vtableProvider, TagRegistry tagRegistry) {
        this.appendable = appendable;
        this.classSource = classSource;
        this.stringPool = stringPool;
        this.layoutProvider = layoutProvider;
        this.vtableProvider = vtableProvider;
        this.tagRegistry = tagRegistry;
    }

    public void renderMethod(MethodReader method) throws IOException {
        if (method.hasModifier(ElementModifier.NATIVE) || method.hasModifier(ElementModifier.ABSTRACT)) {
            return;
        }

        String methodName = method.getReference().toString();
        methodNameSize = methodName.length() + 1;
        methodNameVar = "@name$" + mangleMethod(method.getReference());
        appendable.append(methodNameVar + " = private constant [" + methodNameSize + " x i8] c\""
                + methodName + "\\00\"\n");

        appendable.append("define ").append(renderType(method.getResultType())).append(" ");
        appendable.append("@").append(mangleMethod(method.getReference())).append("(");
        List<String> parameters = new ArrayList<>();
        if (!method.hasModifier(ElementModifier.STATIC)) {
            parameters.add("i8* %v0");
        }
        for (int i = 0; i < method.parameterCount(); ++i) {
            String type = renderType(method.parameterType(i));
            parameters.add(type + " %v" + (i + 1));
        }
        appendable.append(parameters.stream().collect(Collectors.joining(", "))).append(") {\n");

        ProgramReader program = method.getProgram();
        if (program != null && program.basicBlockCount() > 0) {
            typeInferer = new TypeInferer();
            typeInferer.inferTypes(program, method.getReference());

            List<IntObjectMap<BitSet>> callSiteLiveIns = findCallSiteLiveIns(method);
            stackFrameSize = getStackFrameSize(callSiteLiveIns);
            returnBlocks.clear();
            returnVariables.clear();

            if (method.hasModifier(ElementModifier.STATIC) && !method.getName().equals("<clinit>")
                    || method.getName().equals("<init>")) {
                appendable.append("    call void @initializer$" + method.getOwnerName() + "()\n");
            }

            if (stackFrameSize > 0) {
                String stackType = "{ %teavm.stackFrame, [" + stackFrameSize + " x i8*] }";
                appendable.append("    %stack = alloca " + stackType + "\n");
                appendable.append("    %stackHeader = getelementptr " + stackType + ", " + stackType + "* %stack, "
                        + "i32 0, i32 0\n");
                appendable.append("    %stackNext = getelementptr %teavm.stackFrame, "
                        + "%teavm.stackFrame* %stackHeader, i32 0, i32 2\n");
                appendable.append("    %stackTop = load %teavm.stackFrame*, %teavm.stackFrame** @teavm.stackTop\n");
                appendable.append("    store %teavm.stackFrame* %stackTop, %teavm.stackFrame** %stackNext\n");
                appendable.append("    store %teavm.stackFrame* %stackHeader, %teavm.stackFrame** @teavm.stackTop\n");
                appendable.append("    %sizePtr = getelementptr %teavm.stackFrame, %teavm.stackFrame* %stackHeader, "
                        + "i32 0, i32 0\n");
                appendable.append("    store i32 " + stackFrameSize + ", i32* %sizePtr\n");
                appendable.append("    %stackData = getelementptr " + stackType + ", " + stackType + "* %stack, "
                        + "i32 0, i32 1\n");
            }

            appendable.append("    br label %b0\n");

            temporaryVariable = 0;
            currentReturnType = method.getResultType();
            List<List<TryCatchJointReader>> outputJoints = ProgramUtils.getOutputJoints(program);

            for (int i = 0; i < program.basicBlockCount(); ++i) {
                IntObjectMap<BitSet> blockLiveIns = callSiteLiveIns.get(i);
                BasicBlockReader block = program.basicBlockAt(i);
                currentLlvmBlock = "b" + block.getIndex();
                appendable.append(currentLlvmBlock + ":\n");

                joints = new HashMap<>();
                for (TryCatchJointReader joint : outputJoints.get(i)) {
                    for (VariableReader jointSource : joint.readSourceVariables()) {
                        joints.computeIfAbsent(jointSource, x -> new ArrayList<>()).add(joint.getReceiver());
                    }
                }

                for (PhiReader phi : block.readPhis()) {
                    String type = renderType(typeInferer.typeOf(phi.getReceiver().getIndex()));
                    appendable.append("    %v" + phi.getReceiver().getIndex() + " = phi " + type);
                    boolean first = true;
                    for (IncomingReader incoming : phi.readIncomings()) {
                        if (!first) {
                            appendable.append(", ");
                        }
                        first = false;
                        appendable.append("[ %v" + incoming.getValue().getIndex() + ", %b"
                                + incoming.getSource().getIndex() + "]");
                    }
                    appendable.append("\n");
                }

                /*expectingException = !block.readTryCatchBlocks().isEmpty();
                if (expectingException) {
                    appendable.append("    %exception" + i + " = call i8* @teavm.catchException()\n");
                    appendable.append("    %caught" + i + " = icmp ne i8* %exception, null\n");
                    appendable.append("    br i1 %caught" + i + ", label %lp" + i + ", label %b" + i + "\n");
                }*/
                for (int j = 0; j < block.instructionCount(); ++j) {
                    this.callSiteLiveIns = blockLiveIns.get(j);
                    updateShadowStack();
                    block.readInstruction(j, reader);
                    flushInstructions();
                }
                /*if (expectingException) {
                    appendable.append("lp" + i + ":\n");
                }*/
            }

            if (stackFrameSize > 0 && !returnBlocks.isEmpty()) {
                appendable.append("exit:\n");
                String returnType = renderType(method.getResultType());
                String returnVariable;
                if (!returnVariables.isEmpty()) {
                    if (returnVariables.size() == 1) {
                        returnVariable = returnVariables.get(0);
                    } else {
                        returnVariable = "%return";
                        StringBuilder sb = new StringBuilder();
                        sb.append("%return = phi " + returnType + " ");
                        for (int i = 0; i < returnVariables.size(); ++i) {
                            if (i > 0) {
                                sb.append(", ");
                            }
                            sb.append("[" + returnVariables.get(i) + ", %" + returnBlocks.get(i) + "]");
                        }
                        appendable.append("    " + sb + "\n");
                    }
                } else {
                    returnVariable = null;
                }
                appendable.append("    %stackRestore = load %teavm.stackFrame*, %teavm.stackFrame** %stackNext\n");
                appendable.append("    store %teavm.stackFrame* %stackRestore, "
                        + "%teavm.stackFrame** @teavm.stackTop;\n");
                if (method.getResultType() == ValueType.VOID) {
                    appendable.append("    ret void\n");
                } else {
                    appendable.append("    ret " + returnType + " " + returnVariable + "\n");
                }
            }
        }

        appendable.append("}\n");
    }

    private List<IntObjectMap<BitSet>> findCallSiteLiveIns(MethodReader method) {
        List<IntObjectMap<BitSet>> liveOut = new ArrayList<>();

        Program program = ProgramUtils.copy(method.getProgram());
        LivenessAnalyzer livenessAnalyzer = new LivenessAnalyzer();
        livenessAnalyzer.analyze(program);
        Graph cfg = ProgramUtils.buildControlFlowGraph(program);
        DefinitionExtractor defExtractor = new DefinitionExtractor();

        for (int i = 0; i < program.basicBlockCount(); ++i) {
            BasicBlock block = program.basicBlockAt(i);
            IntObjectMap<BitSet> blockLiveIn = new IntObjectOpenHashMap<>();
            liveOut.add(blockLiveIn);
            BitSet currentLiveOut = new BitSet();
            for (int successor : cfg.outgoingEdges(i)) {
                currentLiveOut.or(livenessAnalyzer.liveIn(successor));
            }
            for (int j = block.getInstructions().size() - 1; j >= 0; --j) {
                Instruction insn = block.getInstructions().get(j);
                insn.acceptVisitor(defExtractor);
                for (Variable definedVar : defExtractor.getDefinedVariables()) {
                    currentLiveOut.clear(definedVar.getIndex());
                }
                if (insn instanceof InvokeInstruction || insn instanceof InitClassInstruction
                        || insn instanceof ConstructInstruction || insn instanceof ConstructArrayInstruction
                        || insn instanceof CloneArrayInstruction) {
                    BitSet csLiveIn = (BitSet) currentLiveOut.clone();
                    for (int v = csLiveIn.nextSetBit(0); v >= 0; v = csLiveIn.nextSetBit(v + 1)) {
                        if (!isReference(v)) {
                            csLiveIn.clear(v);
                        }
                    }
                    csLiveIn.clear(0, method.parameterCount() + 1);
                    blockLiveIn.put(j, csLiveIn);
                }
            }
        }

        return liveOut;
    }

    private void updateShadowStack() {
        if (callSiteLiveIns != null && stackFrameSize > 0) {
            String stackType = "[" + stackFrameSize + " x i8*]";
            int cellIndex = 0;
            for (int i = callSiteLiveIns.nextSetBit(0); i >= 0; i = callSiteLiveIns.nextSetBit(i + 1)) {
                String stackCell = "%t" + temporaryVariable++;
                emitted.add(stackCell + " = getelementptr " + stackType + ", " + stackType + "* %stackData, "
                        + "i32 0, i32 " + cellIndex++);
                emitted.add("store i8* %v" + i + ", i8**" + stackCell);
            }
            while (cellIndex < stackFrameSize) {
                String stackCell = "%t" + temporaryVariable++;
                emitted.add(stackCell + " = getelementptr " + stackType + ", " + stackType + "* %stackData, "
                        + "i32 0, i32 " + cellIndex++);
                emitted.add("store i8* null, i8**" + stackCell);
            }
        }
    }

    private boolean isReference(int var) {
        VariableType liveType = typeInferer.typeOf(var);
        switch (liveType) {
            case BYTE_ARRAY:
            case CHAR_ARRAY:
            case SHORT_ARRAY:
            case INT_ARRAY:
            case FLOAT_ARRAY:
            case LONG_ARRAY:
            case DOUBLE_ARRAY:
            case OBJECT_ARRAY:
            case OBJECT:
                return true;
            default:
                return false;
        }
    }

    private int getStackFrameSize(List<IntObjectMap<BitSet>> liveIn) {
        int max = 0;
        for (IntObjectMap<BitSet> blockLiveOut : liveIn) {
            for (ObjectCursor<BitSet> callSiteLiveOutCursor : blockLiveOut.values()) {
                BitSet callSiteLiveOut = callSiteLiveOutCursor.value;
                max = Math.max(max, callSiteLiveOut.cardinality());
            }
        }
        return max;
    }

    private void flushInstructions() throws IOException {
        for (String emittedLine : emitted) {
            appendable.append("    " + emittedLine + "\n");
        }
        emitted.clear();
    }

    private InstructionReader reader = new InstructionReader() {
        @Override
        public void location(InstructionLocation location) {
        }

        @Override
        public void nop() {
        }

        @Override
        public void classConstant(VariableReader receiver, ValueType cst) {
            emitted.add("%v" + receiver.getIndex() + " = bitcast i8* null to i8 *");
        }

        @Override
        public void nullConstant(VariableReader receiver) {
            emitted.add("%v" + receiver.getIndex() + " = bitcast i8* null to i8 *");
        }

        @Override
        public void integerConstant(VariableReader receiver, int cst) {
            emitted.add("%v" + receiver.getIndex() + " = add i32 " + cst + ", 0");
        }

        @Override
        public void longConstant(VariableReader receiver, long cst) {
            emitted.add("%v" + receiver.getIndex() + " = add i64 " + cst + ", 0");
        }

        @Override
        public void floatConstant(VariableReader receiver, float cst) {
            String constantString = "0x" + Long.toHexString(Double.doubleToLongBits(cst));
            emitted.add("%v" + receiver.getIndex() + " = fadd float " + constantString + ", 0.0");
        }

        @Override
        public void doubleConstant(VariableReader receiver, double cst) {
            String constantString = "0x" + Long.toHexString(Double.doubleToLongBits(cst));
            emitted.add("%v" + receiver.getIndex() + " = fadd double " + constantString + ", 0.0");
        }

        @Override
        public void stringConstant(VariableReader receiver, String cst) {
            int index = stringPool.lookup(cst);
            emitted.add("%v" + receiver.getIndex() + " = bitcast %class.java.lang.String* @teavm.str."
                    + index + " to i8*");
        }

        @Override
        public void binary(BinaryOperation op, VariableReader receiver, VariableReader first, VariableReader second,
                NumericOperandType type) {
            StringBuilder sb = new StringBuilder();
            sb.append("%v" + receiver.getIndex() + " = ");
            boolean isFloat = type == NumericOperandType.FLOAT || type == NumericOperandType.DOUBLE;
            String typeStr = getLLVMType(type);

            String secondString = "%v" + second.getIndex();
            switch (op) {
                case ADD:
                    sb.append(isFloat ? "fadd" : "add");
                    break;
                case SUBTRACT:
                    sb.append(isFloat ? "fsub" : "sub");
                    break;
                case MULTIPLY:
                    sb.append(isFloat ? "fmul" : "mul");
                    break;
                case DIVIDE:
                    sb.append(isFloat ? "fdiv" : "sdiv");
                    break;
                case MODULO:
                    sb.append(isFloat ? "frem" : "srem");
                    break;
                case AND:
                    sb.append("and");
                    break;
                case OR:
                    sb.append("or");
                    break;
                case XOR:
                    sb.append("xor");
                    break;
                case SHIFT_LEFT:
                    sb.append("shl");
                    break;
                case SHIFT_RIGHT:
                    sb.append("ashr");
                    break;
                case SHIFT_RIGHT_UNSIGNED:
                    sb.append("lshr");
                    break;
                case COMPARE:
                    sb.append("call i32 @teavm.cmp.");
                    sb.append(typeStr + "(" + typeStr + " %v" + first.getIndex() + ", "
                            + typeStr + " %v" + second.getIndex() + ")");
                    emitted.add(sb.toString());
                    return;
            }
            if (type == NumericOperandType.LONG) {
                switch (op) {
                    case SHIFT_LEFT:
                    case SHIFT_RIGHT:
                    case SHIFT_RIGHT_UNSIGNED: {
                        int tmp = temporaryVariable++;
                        emitted.add("%t" + tmp + " = sext i32 " + secondString + " to i64");
                        secondString = "%t" + tmp;
                        break;
                    }
                    default:
                        break;
                }
            }

            sb.append(" ").append(typeStr).append(" %v" + first.getIndex() + ", " + secondString);
            emitted.add(sb.toString());
        }

        @Override
        public void negate(VariableReader receiver, VariableReader operand, NumericOperandType type) {
            emitted.add("%v" + receiver.getIndex() + " = sub " + getLLVMType(type) + " 0, %v" + operand.getIndex());
        }

        @Override
        public void assign(VariableReader receiver, VariableReader assignee) {
            String type = renderType(typeInferer.typeOf(receiver.getIndex()));
            emitted.add("%v" + receiver.getIndex() + " = bitcast " + type + " %v" + assignee.getIndex()
                    + " to " + type);
        }

        @Override
        public void cast(VariableReader receiver, VariableReader value, ValueType targetType) {
            emitted.add("%v" + receiver.getIndex() + " = bitcast i8* %v" + value.getIndex() + " to i8*");
        }

        @Override
        public void cast(VariableReader receiver, VariableReader value, NumericOperandType sourceType,
                NumericOperandType targetType) {
            switch (sourceType) {
                case INT:
                    switch (targetType) {
                        case INT:
                        case LONG:
                            emitted.add("%v" + receiver.getIndex() + " = sext i32 %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                        case FLOAT:
                        case DOUBLE:
                            emitted.add("%v" + receiver.getIndex() + " = sitofp i32 %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                    }
                    break;
                case LONG:
                    switch (targetType) {
                        case INT:
                        case LONG:
                            emitted.add("%v" + receiver.getIndex() + " = trunc i64 %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                        case FLOAT:
                        case DOUBLE:
                            emitted.add("%v" + receiver.getIndex() + " = sitofp i64 %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                    }
                    break;
                case FLOAT:
                    switch (targetType) {
                        case INT:
                        case LONG:
                            emitted.add("%v" + receiver.getIndex() + " = fptosi float %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                        case FLOAT:
                        case DOUBLE:
                            emitted.add("%v" + receiver.getIndex() + " = fpext float %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                    }
                    break;
                case DOUBLE:
                    switch (targetType) {
                        case INT:
                        case LONG:
                            emitted.add("%v" + receiver.getIndex() + " = fptosi double %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                        case FLOAT:
                        case DOUBLE:
                            emitted.add("%v" + receiver.getIndex() + " = fptrunc double %v" + value.getIndex() + " to "
                                    + getLLVMType(targetType));
                            break;
                    }
                    break;
            }
        }

        @Override
        public void cast(VariableReader receiver, VariableReader value, IntegerSubtype type,
                CastIntegerDirection direction) {
            int tmp = temporaryVariable++;
            switch (direction) {
                case TO_INTEGER:
                    emitted.add("%v" + receiver.getIndex() + " = bitcast i32 %v" + value.getIndex() + " to i32");
                    break;
                case FROM_INTEGER:
                    switch (type) {
                        case BYTE:
                            emitted.add("%t" + tmp + " = trunc i32 %v" + value.getIndex() + " to i8");
                            emitted.add("%v" + receiver.getIndex() + " = sext i8 %t" + tmp + " to i32");
                            break;
                        case SHORT:
                            emitted.add("%t" + tmp + " = trunc i32 %v" + value.getIndex() + " to i16");
                            emitted.add("%t" + receiver.getIndex() + " = sext i16 %t" + tmp + " to i32");
                            break;
                        case CHARACTER:
                            emitted.add("%t" + tmp + " = trunc i32 %v" + value.getIndex() + " to i16");
                            emitted.add("%v" + receiver.getIndex() + " = zext i16 %t" + tmp + " to i32");
                            break;
                    }
                    break;
            }
        }

        @Override
        public void jumpIf(BranchingCondition cond, VariableReader operand, BasicBlockReader consequent,
                BasicBlockReader alternative) {
            int tmp = temporaryVariable++;
            String type = "i32";
            String second = "0";
            if (cond == BranchingCondition.NULL || cond == BranchingCondition.NOT_NULL) {
                type = "i8*";
                second = "null";
            }

            emitted.add("%t" + tmp + " = icmp " + getLLVMOperation(cond) + " " + type
                    + " %v" + operand.getIndex() + ", " + second);
            if (expectingException) {
                emitted.add("call void @teavm.leaveException()");
            }
            emitted.add("br i1 %t" + tmp + ", label %b" + consequent.getIndex() + ", label %b"
                    + alternative.getIndex());
        }

        @Override
        public void jumpIf(BinaryBranchingCondition cond, VariableReader first, VariableReader second,
                BasicBlockReader consequent, BasicBlockReader alternative) {
            int tmp = temporaryVariable++;

            String type = "i32";
            String op;
            switch (cond) {
                case EQUAL:
                    op = "eq";
                    break;
                case NOT_EQUAL:
                    op = "ne";
                    break;
                case REFERENCE_EQUAL:
                    op = "eq";
                    type = "i8*";
                    break;
                case REFERENCE_NOT_EQUAL:
                    op = "ne";
                    type = "i8*";
                    break;
                default:
                    throw new IllegalArgumentException("Unknown condition: " + cond);
            }

            emitted.add("%t" + tmp + " = icmp " + op + " " + type + " %v" + first.getIndex()
                    + ", %v" + second.getIndex());
            if (expectingException) {
                emitted.add("call void @teavm.leaveException()");
            }
            emitted.add("br i1 %t" + tmp + ", label %b" + consequent.getIndex() + ", label %b"
                    + alternative.getIndex());
        }

        @Override
        public void jump(BasicBlockReader target) {
            if (expectingException) {
                emitted.add("call void @teavm.leaveException()");
            }
            emitted.add("br label %b" + target.getIndex());
        }

        @Override
        public void choose(VariableReader condition, List<? extends SwitchTableEntryReader> table,
                BasicBlockReader defaultTarget) {
            StringBuilder sb = new StringBuilder();
            sb.append("switch i32 %v" + condition.getIndex() + ", label %b" + defaultTarget.getIndex() + " [");
            for (SwitchTableEntryReader entry : table) {
                sb.append(" i32 " + entry.getCondition() + ", label %b" + entry.getTarget().getIndex());
            }
            sb.append(" ]");
            emitted.add(sb.toString());
        }

        @Override
        public void exit(VariableReader valueToReturn) {
            if (expectingException) {
                emitted.add("call void @teavm.leaveException()");
            }
            if (valueToReturn == null) {
                if (stackFrameSize == 0) {
                    emitted.add("ret void");
                } else {
                    emitted.add("br label %exit");
                }
            } else {
                VariableType type = typeInferer.typeOf(valueToReturn.getIndex());
                String returnVar = "%v" + valueToReturn.getIndex();
                if (stackFrameSize == 0) {
                    emitted.add("ret " + renderType(type) + " " + returnVar);
                } else {
                    returnVariables.add(returnVar);
                    emitted.add("br label %exit");
                }
            }
            if (stackFrameSize > 0) {
                returnBlocks.add(currentLlvmBlock);
            }
        }

        @Override
        public void raise(VariableReader exception) {
            int tmp = temporaryVariable++;
            int methodName = temporaryVariable++;
            emitted.add("%t" + tmp + " = getelementptr [26 x i8], [26 x i8]* @teavm.exceptionOccurred, i32 0, i32 0");
            emitted.add("%t" + methodName + " = getelementptr [" + methodNameSize + " x i8], "
                    + "[" + methodNameSize + " x i8]* " + methodNameVar + ", i32 0, i32 0");
            emitted.add("call i32 (i8*, ...) @printf(i8* %t" + tmp + ", i8* %t" + methodName + ")");
            emitted.add("call void @exit(i32 255)");
            if (currentReturnType == ValueType.VOID) {
                emitted.add("ret void");
            } else {
                emitted.add("ret " + renderType(currentReturnType) + " " + defaultValue(currentReturnType));
            }
        }

        @Override
        public void createArray(VariableReader receiver, ValueType itemType, VariableReader size) {
            if (itemType instanceof ValueType.Primitive) {
                String functionName = getJavaTypeName(((ValueType.Primitive) itemType).getKind());
                functionName = "@teavm_" + functionName + "ArrayAlloc";
                emitted.add("%v" + receiver.getIndex() + " = call i8* " + functionName
                        + "(i32 %v" + size.getIndex() + ")");
                return;
            }

            int depth = 0;
            while (itemType instanceof ValueType.Array) {
                ++depth;
                itemType = ((ValueType.Array) itemType).getItemType();
            }

            String itemTypeRef;
            if (itemType instanceof ValueType.Object) {
                String className = ((ValueType.Object) itemType).getClassName();
                itemTypeRef = "%vtable." + className + "* @vtable." + className;
            } else if (itemType instanceof ValueType.Primitive) {
                String primitiveName = getJavaTypeName(((ValueType.Primitive) itemType).getKind());
                itemTypeRef = "%itable* @teavm." + primitiveName + "Array";
            } else {
                throw new AssertionError("Type is not expected here: " + itemType);
            }

            String tag = "i32 lshr (i32 ptrtoint (" + itemTypeRef + " to i32), i32 3)";
            emitted.add("%v" + receiver.getIndex() + " = call i8* @teavm_objectArrayAlloc(" + tag
                    + ", i8 " + depth + ", i32 %v" + size.getIndex() + ")");
        }

        @Override
        public void createArray(VariableReader receiver, ValueType itemType,
                List<? extends VariableReader> dimensions) {

        }

        @Override
        public void create(VariableReader receiver, String type) {
            String typeRef = "vtable." + type;
            String tag = "i32 lshr (i32 ptrtoint (%" + typeRef + "* @" + typeRef + " to i32), i32 3)";
            emitted.add("%v" + receiver.getIndex() + " = call i8* @teavm_alloc(" + tag + ")");
        }

        private int sizeOf(String typeRef, String count) {
            int temporaryPointer = temporaryVariable++;
            int sizeOfVar = temporaryVariable++;
            emitted.add("%t" + temporaryPointer + " = getelementptr " + typeRef + ", " + typeRef
                    + "* null, i32 " + count);
            emitted.add("%t" + sizeOfVar + " = ptrtoint " + typeRef + "* %t" + temporaryPointer + " to i32");
            return sizeOfVar;
        }

        @Override
        public void getField(VariableReader receiver, VariableReader instance, FieldReference field,
                ValueType fieldType) {
            String valueTypeRef = renderType(fieldType);
            if (instance == null) {
                emitted.add("%v" + receiver.getIndex() + " = load " + valueTypeRef + ", "
                        + valueTypeRef + "* @" + mangleField(field));
            } else {
                int typedInstance = temporaryVariable++;
                int pointer = temporaryVariable++;
                String typeRef = "%class." + field.getClassName();
                emitted.add("%t" + typedInstance + " = bitcast i8* %v" + instance.getIndex() + " to " + typeRef + "*");
                emitted.add("%t" + pointer + " = getelementptr " + typeRef + ", " + typeRef + "* "
                        + "%t" + typedInstance + ", i32 0, i32 " + layoutProvider.getIndex(field));
                emitted.add("%v" + receiver.getIndex() + " = load " + valueTypeRef + ", "
                        + valueTypeRef + "* %t" + pointer);
            }
        }

        @Override
        public void putField(VariableReader instance, FieldReference field, VariableReader value, ValueType fieldType) {
            String valueTypeRef = renderType(fieldType);
            if (instance == null) {
                emitted.add("store " + valueTypeRef + " %v" + value.getIndex() + ", "
                        + valueTypeRef + "* @" + mangleField(field));
            } else {
                int typedInstance = temporaryVariable++;
                int pointer = temporaryVariable++;
                String typeRef = "%class." + field.getClassName();
                emitted.add("%t" + typedInstance + " = bitcast i8* %v" + instance.getIndex() + " to " + typeRef + "*");
                emitted.add("%t" + pointer + " = getelementptr " + typeRef + ", " + typeRef + "* "
                        + "%t" + typedInstance + ", i32 0, i32 " + layoutProvider.getIndex(field));
                emitted.add("store " + valueTypeRef + " %v" + value.getIndex() + ", "
                        + valueTypeRef + "* %t" + pointer);
            }
        }

        @Override
        public void arrayLength(VariableReader receiver, VariableReader array) {
            arrayLength(array, "%v" + receiver.getIndex());
        }

        private void arrayLength(VariableReader array, String target) {
            int objectRef = temporaryVariable++;
            int headerRef = temporaryVariable++;
            emitted.add("%t" + objectRef + " = bitcast i8* %v" + array.getIndex() + " to %teavm.Array*");
            emitted.add("%t" + headerRef + " = getelementptr %teavm.Array, %teavm.Array* %t"
                    + objectRef + ", i32 0, i32 1");
            emitted.add(target + " = load i32, i32* %t" + headerRef);
        }

        @Override
        public void cloneArray(VariableReader receiver, VariableReader array) {
            emitted.add("%v" + receiver.getIndex() + " = call i8* @teavm_cloneArray(i8* %v" + array.getIndex() + ")");
        }

        @Override
        public void unwrapArray(VariableReader receiver, VariableReader array, ArrayElementType elementType) {
            emitted.add("%v" + receiver.getIndex() + " = bitcast i8* %v" + array.getIndex() + " to i8*");
        }

        @Override
        public void getElement(VariableReader receiver, VariableReader array, VariableReader index) {
            String type = renderType(typeInferer.typeOf(receiver.getIndex()));
            VariableType itemType = typeInferer.typeOf(array.getIndex());
            String itemTypeStr = renderItemType(itemType);
            int elementRef = getArrayElementReference(array, index, itemTypeStr);
            if (type.equals(itemTypeStr)) {
                emitted.add("%v" + receiver.getIndex() + " = load " + type + ", " + type + "* %t" + elementRef);
            } else {
                int tmp = temporaryVariable++;
                emitted.add("%t" + tmp + " = load " + itemTypeStr + ", " + itemTypeStr + "* %t" + elementRef);
                switch (itemType) {
                    case BYTE_ARRAY:
                        emitted.add("%v" + receiver.getIndex() + " = sext i8 %t" + tmp + " to i32");
                        break;
                    case SHORT_ARRAY:
                        emitted.add("%v" + receiver.getIndex() + " = sext i16 %t" + tmp + " to i32");
                        break;
                    case CHAR_ARRAY:
                        emitted.add("%v" + receiver.getIndex() + " = zext i16 %t" + tmp + " to i32");
                        break;
                    default:
                        throw new AssertionError("Should not get here");
                }
            }
        }

        @Override
        public void putElement(VariableReader array, VariableReader index, VariableReader value) {
            String type = renderType(typeInferer.typeOf(value.getIndex()));
            VariableType itemType = typeInferer.typeOf(array.getIndex());
            String itemTypeStr = renderItemType(itemType);
            int elementRef = getArrayElementReference(array, index, itemTypeStr);
            String valueRef = "%v" + value.getIndex();
            if (!type.equals(itemTypeStr)) {
                int tmp = temporaryVariable++;
                emitted.add("%t" + tmp + " = trunc i32 " + valueRef + " to " + itemTypeStr);
                valueRef = "%t" + tmp;
            }
            emitted.add("store " + itemTypeStr + " " + valueRef + ", " + itemTypeStr + "* %t" + elementRef);
        }

        private int getArrayElementReference(VariableReader array, VariableReader index, String type) {
            int objectRef = temporaryVariable++;
            int dataRef = temporaryVariable++;
            int typedDataRef = temporaryVariable++;
            int adjustedIndex = temporaryVariable++;
            int elementRef = temporaryVariable++;
            emitted.add("%t" + objectRef + " = bitcast i8* %v" + array.getIndex() + " to %teavm.Array*");
            emitted.add("%t" + dataRef + " = getelementptr %teavm.Array, %teavm.Array* %t"
                    + objectRef + ", i32 1");
            emitted.add("%t" + typedDataRef + " = bitcast %teavm.Array* %t" + dataRef + " to " + type + "*");
            emitted.add("%t" + adjustedIndex + " = add i32 %v" + index.getIndex() + ", 1");
            emitted.add("%t" + elementRef + " = getelementptr " + type + ", " + type + "* %t" + typedDataRef
                    + ", i32 %t" + adjustedIndex);

            return elementRef;
        }

        @Override
        public void invoke(VariableReader receiver, VariableReader instance, MethodReference method,
                List<? extends VariableReader> arguments, InvocationType type) {
            StringBuilder sb = new StringBuilder();
            if (receiver != null) {
                sb.append("%v" + receiver.getIndex() + " = ");
            }

            String functionText;
            if (type == InvocationType.SPECIAL) {
                functionText = "@" + mangleMethod(method);
            } else {
                VirtualTableEntry entry = resolve(method);
                String className = entry.getVirtualTable().getClassName();
                String typeRef = className != null ? "%vtable." + className : "%itable";
                int objectRef = temporaryVariable++;
                int headerFieldRef = temporaryVariable++;
                int vtableTag = temporaryVariable++;
                int vtableRef = temporaryVariable++;
                int vtableTypedRef = temporaryVariable++;
                emitted.add("%t" + objectRef + " = bitcast i8* %v" + instance.getIndex() + " to %teavm.Object*");
                emitted.add("%t" + headerFieldRef + " = getelementptr inbounds %teavm.Object, %teavm.Object* %t"
                        + objectRef + ", i32 0, i32 0");
                emitted.add("%t" + vtableTag + " = load i32, i32* %t" + headerFieldRef);
                emitted.add("%t" + vtableRef + " = shl i32 %t" + vtableTag + ", 3");
                emitted.add("%t" + vtableTypedRef + " = inttoptr i32 %t" + vtableRef + " to " + typeRef + "*");

                int functionRef = temporaryVariable++;
                int vtableIndex = entry.getIndex() + 1;

                emitted.add("%t" + functionRef + " = getelementptr inbounds " + typeRef + ", "
                        + typeRef + "* %t" + vtableTypedRef + ", i32 0, i32 " + vtableIndex);
                int function = temporaryVariable++;
                String methodType = methodType(method.getDescriptor());
                emitted.add("%t" + function + " = load " + methodType + ", " + methodType + "* %t" + functionRef);

                functionText = "%t" + function;
            }

            sb.append("call " + renderType(method.getReturnType()) + " " + functionText + "(");

            List<String> argumentStrings = new ArrayList<>();
            if (instance != null) {
                argumentStrings.add("i8* %v" + instance.getIndex());
            }
            for (int i = 0; i < arguments.size(); ++i) {
                argumentStrings.add(renderType(method.parameterType(i)) + " %v" + arguments.get(i).getIndex());
            }
            sb.append(argumentStrings.stream().collect(Collectors.joining(", ")) + ")");

            emitted.add(sb.toString());
        }

        private VirtualTableEntry resolve(MethodReference method) {
            while (true) {
                VirtualTableEntry entry = vtableProvider.lookup(method);
                if (entry != null) {
                    return entry;
                }
                ClassReader cls = classSource.get(method.getClassName());
                if (cls == null || cls.getParent() == null || cls.getParent().equals(cls.getName())) {
                    break;
                }
                method = new MethodReference(cls.getParent(), method.getDescriptor());
            }
            return null;
        }

        @Override
        public void invokeDynamic(VariableReader receiver, VariableReader instance, MethodDescriptor method,
                List<? extends VariableReader> arguments, MethodHandle bootstrapMethod,
                List<RuntimeConstant> bootstrapArguments) {

        }

        @Override
        public void isInstance(VariableReader receiver, VariableReader value, ValueType type) {
            if (type instanceof ValueType.Object) {
                String className = ((ValueType.Object) type).getClassName();
                List<TagRegistry.Range> ranges = tagRegistry.getRanges(className);

                if (!ranges.isEmpty()) {
                    String headerRef = "%t" + temporaryVariable++;
                    emitted.add(headerRef + " = bitcast i8* %v" + value.getIndex() + " to %teavm.Object*");
                    String vtableRefRef = "%t" + temporaryVariable++;
                    emitted.add(vtableRefRef + " = getelementptr %teavm.Object, %teavm.Object* " + headerRef + ", "
                            + "i32 0, i32 0");
                    String vtableTag = "%t" + temporaryVariable++;
                    emitted.add(vtableTag + " = load i32, i32* " + vtableRefRef);
                    String vtableRef = "%t" + temporaryVariable++;
                    emitted.add(vtableRef + " = shl i32 " + vtableTag + ", 3");
                    String typedVtableRef = "%t" + temporaryVariable++;
                    emitted.add(typedVtableRef + " = inttoptr i32 " + vtableRef + " to %teavm.Class*");
                    String tagRef = "%t" + temporaryVariable++;
                    emitted.add(tagRef + " = getelementptr %teavm.Class, %teavm.Class* " + typedVtableRef
                            + ", i32 0, i32 2");
                    String tag = "%t" + temporaryVariable++;
                    emitted.add(tag + " = load i32, i32* " + tagRef);

                    String trueLabel = "tb" + temporaryVariable++;
                    String finalLabel = "tb" + temporaryVariable++;
                    String next = null;
                    for (TagRegistry.Range range : ranges) {
                        String tmpLabel = "tb" + temporaryVariable++;
                        next = "tb" + temporaryVariable++;
                        String tmpLower = "%t" + temporaryVariable++;
                        String tmpUpper = "%t" + temporaryVariable++;
                        emitted.add(tmpLower + " = icmp slt i32 " + tag + ", " + range.lower);
                        emitted.add("br i1 " + tmpLower + ", label %" + next + ", label %" + tmpLabel);
                        emitted.add(tmpLabel + ":");
                        emitted.add(tmpUpper + " = icmp sge i32 " + tag + ", " + range.upper);
                        emitted.add("br i1 " + tmpUpper + ", label %" + next + ", label %" + trueLabel);
                        emitted.add(next + ":");
                    }

                    String falseVar = "%t" + temporaryVariable++;
                    emitted.add(falseVar + " = add i32 0, 0");
                    emitted.add("br label %" + finalLabel);

                    String trueVar = "%t" + temporaryVariable++;
                    emitted.add(trueLabel + ":");
                    emitted.add(trueVar + " = add i32 1, 0");
                    emitted.add("br label %" + finalLabel);

                    String phiVar = "%t" + temporaryVariable++;
                    emitted.add(finalLabel + ":");
                    emitted.add(phiVar + " = phi i32 [ " + trueVar + ", "
                            + "%" + trueLabel + " ], [ " + falseVar + ", %" + next + "]");
                    emitted.add("%v" + receiver.getIndex() + " = add i32 0, " + phiVar);

                    currentLlvmBlock = finalLabel;
                } else {
                    emitted.add("%v" + receiver.getIndex() + " = add i32 0, 0");
                }
            } else {
                emitted.add("%v" + receiver.getIndex() + " = add i32 1, 0");
            }
        }

        @Override
        public void initClass(String className) {
            emitted.add("call void @initializer$" + className + "()");
        }

        @Override
        public void nullCheck(VariableReader receiver, VariableReader value) {
        }

        @Override
        public void monitorEnter(VariableReader objectRef) {
        }

        @Override
        public void monitorExit(VariableReader objectRef) {
        }
    };

    private static String getLLVMType(NumericOperandType type) {
        switch (type) {
            case INT:
                return "i32";
            case LONG:
                return "i64";
            case FLOAT:
                return "float";
            case DOUBLE:
                return "double";
        }
        throw new IllegalArgumentException("Unknown operand type: " + type);
    }

    private static String getLLVMOperation(BranchingCondition cond) {
        switch (cond) {
            case EQUAL:
            case NULL:
                return "eq";
            case NOT_NULL:
            case NOT_EQUAL:
                return "ne";
            case GREATER:
                return "sgt";
            case GREATER_OR_EQUAL:
                return "sge";
            case LESS:
                return "slt";
            case LESS_OR_EQUAL:
                return "sle";
        }
        throw new IllegalArgumentException("Unsupported condition: " + cond);
    }
}

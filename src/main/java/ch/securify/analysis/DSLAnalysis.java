package ch.securify.analysis;

import ch.securify.decompiler.Variable;
import ch.securify.decompiler.instructions.*;
import ch.securify.dslpatterns.DSLPatternResult;
import ch.securify.dslpatterns.DSLPatternsCompiler;
import ch.securify.utils.BigIntUtil;
import ch.securify.utils.CommandRunner;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class DSLAnalysis {

    public static final int UNK_CONST_VAL = -1;

    protected List<Instruction> instructions;

    protected BiMap<Variable, Integer> varToCode;
    protected BiMap<Instruction, Integer> instrToCode;
    protected BiMap<Class, Integer> typeToCode;
    protected BiMap<Integer, Integer> constToCode;

    protected BiMap<Integer, Variable> offsetToStorageVar;
    protected BiMap<Integer, Variable> offsetToMemoryVar;
    protected BiMap<String, StringBuffer> ruleToSB;
    protected Map<String, Set<Long>> fixedpoint;

    protected int bvCounter = 1; //reserve 0 for the constant 0

    public int unk;

    protected final boolean DEBUG = true;
    protected final boolean CREATE_THING_TO_INTEGER_FILE_MAP = true;
    BufferedWriter thingToIntegerFileWriter;

    // input predicates

    protected String WORKSPACE, WORKSPACE_OUT;
    protected final String TIMEOUT_COMMAND = System.getProperty("os.name").toLowerCase().startsWith("mac") ? "gtimeout" : "timeout";

    public DSLAnalysis() throws IOException, InterruptedException {
        initDataflow();
    }


    protected void initDataflow() throws IOException, InterruptedException {
        // create workspace
        Random rnd = new Random();
        WORKSPACE = (new File(System.getProperty("java.io.tmpdir"), "souffle-" + UUID.randomUUID())).getAbsolutePath();
        WORKSPACE_OUT = WORKSPACE + "_OUT";
        CommandRunner.runCommand("mkdir " + WORKSPACE);
        CommandRunner.runCommand("mkdir " + WORKSPACE_OUT);

        varToCode = HashBiMap.create();
        instrToCode = HashBiMap.create();
        typeToCode = HashBiMap.create();
        constToCode = HashBiMap.create();
        fixedpoint = new HashMap<>();

        //const 0 maps to 0
        constToCode.put(new Integer(0), new Integer(0));

        if(CREATE_THING_TO_INTEGER_FILE_MAP)
            thingToIntegerFileWriter = new BufferedWriter(new FileWriter(new File(WORKSPACE_OUT + "/thingToIntegerMap.txt")));


        //fill in already the hashmap of types so that they always the same
        getCode(CallDataLoad.class);
        getCode(SLoad.class);
        getCode(Balance.class);
        getCode(Caller.class);


        offsetToStorageVar = HashBiMap.create();
        offsetToMemoryVar = HashBiMap.create();

        ruleToSB = HashBiMap.create();
        ruleToSB.put("assignVar", new StringBuffer());
        ruleToSB.put("assignType", new StringBuffer());
        ruleToSB.put("taint", new StringBuffer());
        ruleToSB.put("followsMayImplicit", new StringBuffer());
        ruleToSB.put("followsMustExplicit", new StringBuffer());
        ruleToSB.put("jump", new StringBuffer());
        ruleToSB.put("jumpDest", new StringBuffer());
        ruleToSB.put("oneBranchJumpDest", new StringBuffer());
        ruleToSB.put("join", new StringBuffer());
        ruleToSB.put("endIf", new StringBuffer());
        ruleToSB.put("mload", new StringBuffer());
        ruleToSB.put("mstore", new StringBuffer());
        ruleToSB.put("sload", new StringBuffer());
        ruleToSB.put("sstore", new StringBuffer());
        //this input doesn't put unk where there is no constant value, but leaves the real variable
        ruleToSB.put("mloadInstr", new StringBuffer());
        ruleToSB.put("mstoreInstr", new StringBuffer());
        ruleToSB.put("sloadInstr", new StringBuffer());
        ruleToSB.put("sstoreInstr", new StringBuffer());
        ruleToSB.put("virtualMethodHead", new StringBuffer());
        ruleToSB.put("noArgsVirtualMethodHead", new StringBuffer());
        ruleToSB.put("goto", new StringBuffer());
        ruleToSB.put("isConst", new StringBuffer());
        ruleToSB.put("hasValue", new StringBuffer());
        ruleToSB.put("isArg", new StringBuffer());
        ruleToSB.put("isStorageVar", new StringBuffer());
        ruleToSB.put("sha3", new StringBuffer());
        ruleToSB.put("call", new StringBuffer());
        ruleToSB.put("unk", new StringBuffer());
        ruleToSB.put("assignVarMayImplicit", new StringBuffer());

        unk = getCode(UNK_CONST_VAL);
        appendRule("unk", unk);

        log("Souffle Analysis");
    }

    public void analyse(List<Instruction> decompiledInstructions) throws IOException, InterruptedException {
        instructions = decompiledInstructions;

        deriveAssignVarPredicates();
        thingToIntegerFileWriter.write("--------------------------------------------\n");
        deriveAssignTypePredicates();
        thingToIntegerFileWriter.write("--------------------------------------------\n");
        deriveInstructionsPredicates();
        deriveIsConstPredicates();

        deriveFollowsPredicates();
        deriveIfPredicates();

        createProgramRulesFile();

        if(CREATE_THING_TO_INTEGER_FILE_MAP)
            thingToIntegerFileWriter.flush();

        long start = System.currentTimeMillis();
        /* run compiled souffle */
        String cmd = TIMEOUT_COMMAND + " " + Config.PATTERN_TIMEOUT + "s " + DSLPatternsCompiler.FINAL_EXECUTABLE + " -F " + WORKSPACE + " -D " + WORKSPACE_OUT;
        log(cmd);
        CommandRunner.runCommand(cmd);

        long elapsedTime = System.currentTimeMillis() - start;
        String elapsedTimeStr = String.format("%d min, %d sec",
                TimeUnit.MILLISECONDS.toMinutes(elapsedTime),
                TimeUnit.MILLISECONDS.toSeconds(elapsedTime) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(elapsedTime))
        );
        log(elapsedTimeStr);
    }

    public static int getInt(byte[] data) {
        byte[] bytes = new byte[4];
        for (int i = 0; i < Math.min(data.length, 4); ++i) {
            bytes[i + 4 - Math.min(data.length, 4)] = data[i];
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        return bb.getInt();
    }


    protected void createProgramRulesFile() {
        for (String rule : ruleToSB.keySet()) {
            BufferedWriter bwr;
            try {
                bwr = new BufferedWriter(new FileWriter(new File(WORKSPACE + "/" + rule + ".facts")));
                bwr.write(ruleToSB.get(rule).toString());
                bwr.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void dispose() throws IOException, InterruptedException {
        if(CREATE_THING_TO_INTEGER_FILE_MAP)
            thingToIntegerFileWriter.close();

        CommandRunner.runCommand("rm -r " + WORKSPACE);
        CommandRunner.runCommand("rm -r " + WORKSPACE_OUT);
    }

    protected void log(String msg) {
        if (DEBUG)
            System.out.println(this.getClass().getSimpleName() + ": " + msg);
    }

    protected void createMStoreRule(Instruction instr, Variable offset, Variable var) {
        appendRule("mstoreInstr", getCode(instr), getCode(offset), getCode(var));
    }

    protected void createSStoreRule(Instruction instr, Variable index, Variable var) {
        appendRule("sstoreInstr", getCode(instr), getCode(index), getCode(var));
    }

    protected void createAssignVarRule(Instruction instr, Variable output, Variable input) {
        appendRule("assignVar", getCode(instr), getCode(output), getCode(input));
    }

    protected void createAssignVarMayImplicitRule(Instruction instr, Variable output, Variable input) {
        appendRule("assignVarMayImplicit", getCode(instr), getCode(output), getCode(input));
    }

    protected void createAssignTypeRule(Instruction instr, Variable var, Class type) {
        appendRule("assignType", getCode(instr), getCode(var), getCode(type));
    }

    protected void createAssignTopRule(Instruction instr, Variable var) {
        appendRule("assignType", getCode(instr), getCode(var), unk);
    }

    protected void createEndIfRule(Instruction start, Instruction end) {
        appendRule("endIf", getCode(start), getCode(end));
    }

    protected void appendRule(String ruleName, Object... args) {
        StringBuffer sb;
        if (ruleToSB.containsKey(ruleName)) {
            sb = ruleToSB.get(ruleName);
        } else {
            throw new RuntimeException("unknown rule: " + ruleName);
        }
        for (int i = 0; i < args.length - 1; i++) {
            sb.append(args[i]);
            sb.append("\t");
        }
        sb.append(args[args.length - 1]);
        sb.append("\n");
    }

    protected int getFreshCode() {
        if (bvCounter == Integer.MAX_VALUE) {
            throw new RuntimeException("Integer overflow.");
        }
        int freshCode = bvCounter;
        bvCounter++;
        return freshCode;
    }

    private void writeOnThingsToIntegerFile(Object toWrite) {
        try {
            thingToIntegerFileWriter.write(toWrite + " " + (bvCounter-1) + " type class: " + toWrite.getClass().getCanonicalName());
            thingToIntegerFileWriter.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int getCode(Variable var) {
        if (!varToCode.containsKey(var)) {
            varToCode.put(var, getFreshCode());
            writeOnThingsToIntegerFile(var);
        }
        return varToCode.get(var);
    }

    public int getCode(Instruction instr) {
        if (!instrToCode.containsKey(instr)) {
            instrToCode.put(instr, getFreshCode());
            writeOnThingsToIntegerFile(instr);
        }
        return instrToCode.get(instr);
    }

    public int getCode(Class instructionClass) {
        if (!typeToCode.containsKey(instructionClass)) {
            typeToCode.put(instructionClass, getFreshCode());
            writeOnThingsToIntegerFile(instructionClass);
        }
        return typeToCode.get(instructionClass);
    }

    public int getCode(Integer constVal) {
        if (!constToCode.containsKey(constVal)) {
            constToCode.put(constVal, getFreshCode());
            writeOnThingsToIntegerFile(constVal);
        }
        return constToCode.get(constVal);
    }

    public int getCode(Object o) {
        if (o instanceof Instruction) {
            return getCode((Instruction) o);
        } else if (o instanceof Class) {
            return getCode((Class) o);
        } else if (o instanceof Integer) {
            return getCode((Integer) o);
        } else if (o instanceof Variable) {
            return getCode((Variable) o);
        } else {
            throw new RuntimeException("Not supported object of a bit vector");
        }
    }

    protected void deriveAssignTypePredicates() {
        log(">> Derive AssignType predicates <<");
        for (Instruction instr : instructions) {
            if (instr instanceof Push
                    || instr instanceof CallValue
                    || instr instanceof Caller
                    || instr instanceof CallDataLoad
                    || instr instanceof CallDataSize
                    || instr instanceof Coinbase
                    || instr instanceof Gas
                    || instr instanceof IsZero
                    || instr instanceof Not
                    || instr instanceof BlockTimestamp
                    || instr instanceof BlockNumber
                    || instr instanceof GasLimit
                    || instr instanceof GasPrice
                    || instr instanceof Balance
                    || instr instanceof Difficulty
                    || instr instanceof SLoad
                    || instr instanceof Address) {
                createAssignTypeRule(instr, instr.getOutput()[0], instr.getClass());
            } else if (instr instanceof Div) {
                if (instr.getInput()[1].hasConstantValue() &&
                        (getInt(instr.getInput()[1].getConstantValue()) == 1
                                // X = Y / 1 , do not taint as value of X does not depend on division in this case
                                || (instr.getInput()[1].getConstantValue().length == 29 && instr.getInput()[1].getConstantValue()[0] == 1)
                                || getInt(instr.getInput()[1].getConstantValue()) == 32
                                || getInt(instr.getInput()[1].getConstantValue()) == 2
                                // X = Y / 10^29 , do not taint as value of X because div by 10^29 is often used for aligning
                        )) {

                    continue;
                }
                createAssignTypeRule(instr, instr.getOutput()[0], instr.getClass());
            } else if (instr instanceof _VirtualMethodHead) {
                log("**** inside _VirtualMethodHead");
                for (Variable arg : instr.getOutput()) {
                    log("**** inside loop");
                    log("Type of " + arg + " is unk");
                    createAssignTopRule(instr, arg);
                    // assign the arguments as an abstract type (to check later
                    // for missing input validation)
                    appendRule("assignType", getCode(instr), getCode(arg), getCode(arg));
                    // tag the arguments to depend on user input (CallDataLoad)
                    createAssignTypeRule(instr, arg, CallDataLoad.class);
                }
            } else if (instr instanceof Call || instr instanceof StaticCall) {
                log("Type of " + instr.getOutput()[0] + " is Call");
                createAssignTopRule(instr, instr.getOutput()[0]);
                // assign the return value as an abstract type (to check later
                // for unhandled exception)
                appendRule("assignType", getCode(instr), getCode(instr.getOutput()[0]), getCode(instr.getOutput()[0]));
            } else if (instr instanceof BlockHash) {
                log("Type of " + instr.getOutput()[0] + " is BlockHash");
                createAssignTypeRule(instr, instr.getOutput()[0], instr.getClass());
                // TODO: double check whether to propagate the type of the
                // argument to the output of blockhash
                createAssignVarRule(instr, instr.getOutput()[0], instr.getInput()[0]);
            } else if (instr instanceof ReturnDataCopy) {
                // TODO: New memory-based rule here
            }
        }
    }

    protected void deriveInstructionsPredicates() { //OK
        log(">> Derive MStore, MLoad, SStore, SLoad, call predicates <<");
        for (Instruction instr : instructions) {
            if (instr instanceof MStore || instr instanceof MStore8) {
                Variable var = instr.getInput()[1];
                Variable offset = instr.getInput()[0];
                log("mstore instruction: " + instr.getStringRepresentation());
                createMStoreRule(instr, offset, var);
            }
            else if (instr instanceof MLoad) {
                log("mload instruction: " + instr.getStringRepresentation());
                Variable var = instr.getOutput()[0];
                Variable offset = instr.getInput()[0];
                createMLoadRule(instr, offset, var);
            }
            else if (instr instanceof SStore) {
                Variable index = instr.getInput()[0];
                Variable var = instr.getInput()[1];
                log("sstore instruction: " + instr.getStringRepresentation());
                createSStoreRule(instr, index, var);
            }
            else if (instr instanceof SLoad) {
                Variable var = instr.getOutput()[0];
                Variable index = instr.getInput()[0];
                log("sload instruction" + instr.getStringRepresentation());
                createSLoadRule(instr, index, var);
            }
            else if (instr instanceof Call) {
                log("call instruction" + instr.getStringRepresentation());
                createCallRule(instr);
            }
            else if (instr instanceof _VirtualMethodHead) {
                log("_VirtualMethodHead instruction" + instr.getStringRepresentation());
                int instrCode = getCode(instr);
                appendRule("virtualMethodHead", instrCode);
                Variable[] args = instr.getOutput();
                if(args.length == 0) {
                    appendRule("noArgsVirtualMethodHead", instrCode);
                }
                else {
                    for(Variable var : args) {
                        appendRule("isArg", getCode(var), instrCode);
                    }
                }
            }
        }
    }

    protected void deriveIsConstPredicates() { //OK
        Set<Variable> constants = new HashSet<>();
        for (Instruction instr : instructions) {
            for(Variable var : instr.getInput()) {
                if(var != null && var.hasConstantValue()) {
                    constants.add(var);
                    log("Added constant: " + var.getName() + " from instr " + instr.getStringRepresentation());
                }
            }

            for(Variable var : instr.getOutput()) {
                if(var.hasConstantValue())
                    constants.add(var);
            }
        }

        constants.forEach(var -> {
        appendRule("isConst", getCode(var));
        if(var.getConstantValue() != Variable.VALUE_ANY && var.getConstantValue() != Variable.VALUE_UNDEFINED) {
            try {
                appendRule("hasValue", getCode(var), BigIntUtil.fromInt256(var.getConstantValue()).intValueExact());
            } catch (ArithmeticException e) {
                log("Value didn't fit into 32 bits souffle number size, skipped");
            }
        }


        log("isConst(" + var + ")");
        log("constValue: " + BigIntUtil.fromInt256(var.getConstantValue()));
        });
    }

    protected void deriveAssignVarPredicates() {
        log(">> Derive assign predicates <<");
        for (Instruction instr : instructions) {
            log(instr.getStringRepresentation());

            if (instr instanceof SLoad) {
                Variable storageOffset = instr.getInput()[0];
                Variable lhs = instr.getOutput()[0];
                if (storageOffset.hasConstantValue()) {
                    int storageOffsetValue = getInt(storageOffset.getConstantValue());
                    //Variable storageVar = getStorageVarForIndex(storageOffsetValue);

                    // big hack: adding an assignType predicate below
                    //appendRule("assignType", getCode(instr), getCode(lhs), getCode(storageVar));
                    appendRule("assignType", getCode(instr), getCode(lhs), storageOffsetValue);
                } else {
                    appendRule("assignType", getCode(instr), getCode(lhs), unk);
                }
            }

            if (instr instanceof MLoad) {
                Variable memoryOffset = instr.getInput()[0];
                Variable lhs = instr.getOutput()[0];
                if (memoryOffset.hasConstantValue()) {
                    int memoryOffsetValue = getInt(memoryOffset.getConstantValue());
                    //Variable memoryVar = getMemoryVarForIndex(memoryOffsetValue);

                    // big hack: adding an assignType predicate below
                    //appendRule("assignType", getCode(instr), getCode(lhs), getCode(memoryVar));
                    appendRule("assignType", getCode(instr), getCode(lhs), memoryOffsetValue);
                } else {
                    appendRule("assignType", getCode(instr), getCode(lhs), unk);
                }
            }

            if (instr instanceof Call || instr instanceof StaticCall) {
                createAssignVarRule(instr, instr.getOutput()[0], instr.getInput()[2]);
            }

            if (instr instanceof Sha3) {
                if (instr.getInput()[0].hasConstantValue() && instr.getInput()[1].hasConstantValue()) {
                    int startOffset = getInt(instr.getInput()[0].getConstantValue());
                    int length = getInt(instr.getInput()[1].getConstantValue());
                    //assert(startOffset % 32 == 0);
                    for (int offset = startOffset; offset < startOffset + length; offset += 4) {
                        log("sha3: " + instr + " " + instr.getOutput()[0]);
                        //log("Offset " + offset + ", memory var " + getMemoryVarForIndex(offset) + ", code " + getCode(getMemoryVarForIndex(offset)));
                        //appendRule("sha3", getCode(instr), getCode(instr.getOutput()[0]), getCode(getMemoryVarForIndex(offset)));
                        appendRule("sha3", getCode(instr), getCode(instr.getOutput()[0]), offset);
                        //since we changed the way to handle sstore and other instructions, we should change also here, removing the new intoduced variables and replacing them with the index
                    }
                } else {
                    // propagate the entire heap to the output of SHA3
                }
            }


            // Skip MSTORE/MLOAD SSTORE/SLOAD as these are handled in a special
            // way
            if (instr instanceof MStore
                    || instr instanceof MLoad
                    || instr instanceof SStore
                    || instr instanceof SLoad
                    || instr instanceof Call
                    || instr instanceof StaticCall
                    || instr instanceof Sha3) {
                continue;
            }

            if (instr instanceof Or) {
                // a = b | c; if b or c is 0, do not propagate their types.
                for (Variable output : instr.getOutput()) {
                    for (Variable input : instr.getInput()) {
                        if (input.hasConstantValue()) {
                            BigInteger val = BigIntUtil.fromInt256(input.getConstantValue());
                            if (val.compareTo(BigInteger.ZERO) == 0) {
                                // Do not propagate this input
                                continue;
                            }
                        }
                        createAssignVarRule(instr, output, input);
                    }
                }
                continue;
            }

            for (Variable output : instr.getOutput()) {
                for (Variable input : instr.getInput()) {
                    createAssignVarRule(instr, output, input);
                }
            }
        }
    }

    protected void deriveFollowsPredicates() { //OK
        log(">> Derive follows predicates <<");
        for (Instruction instr : instructions) {

            //this if is the only difference in this method between mayImplicit dataflow and mustExplicit, so
            //we just leave it here, since the jumpDest and oneBranchJumpDest are only used in mustExplicit
            if (instr instanceof JumpDest) {
                if (((JumpDest) instr).getIncomingBranches().size() == 1 && instr.getPrev() == null) {
                    log("One-Branch Tag fact: " + instr);
                    appendRule("oneBranchJumpDest", getCode(instr));
                }
                log("Tag fact (jumpDest): " + instr);
                appendRule("jumpDest", getCode(instr));
            }

            if (instr instanceof BranchInstruction) {
                BranchInstruction branchInstruction = (BranchInstruction) instr;
                for (Instruction outgoingInstruction : branchInstruction.getOutgoingBranches()) {
                    if (!(outgoingInstruction instanceof _VirtualMethodHead)) {
                        createFollowsRule(instr, outgoingInstruction);
                    }
                }
            }
            Instruction nextInstruction = instr.getNext();

            if (nextInstruction != null) {
                createFollowsRule(instr, nextInstruction);
            }
        }
    }

    private void createFollowsRule(Instruction from, Instruction to) { //OK
        //mayImplicit part
        appendRule("followsMayImplicit", getCode(from), getCode(to));

        //mustExplicit part, jump and joi are only in mustExplicit
        if (from instanceof JumpI) {
            Instruction mergeInstruction = ((JumpI)from).getMergeInstruction();
            if (mergeInstruction == null) {
                mergeInstruction = new JumpDest("BLACKHOLE");
            }
            if (!(to instanceof JumpDest)) {
                appendRule("followsMustExplicit", getCode(from), getCode(to));
            }
            appendRule("jump", getCode(from), getCode(to), getCode(mergeInstruction));
        } else if (from instanceof Jump) {
            // need to use a jump, not follows because follows ignores the TO if it is of type Tag, see Datalog rules
            appendRule("jump", getCode(from), getCode(to), getCode(to));
        } else {
            appendRule("followsMustExplicit", getCode(from), getCode(to));
        }

        if (to instanceof JumpDest) {
            //appendRule("join", getCode(from), getCode(to));
            List<Instruction> incomingBranches = new ArrayList<Instruction>(((JumpDest) to).getIncomingBranches());
            if (to.getPrev() != null) {
                incomingBranches.add(to.getPrev());
            }
            log("JumpDest: " + to + " with incoming branches: " + incomingBranches);

//            if (incomingBranches.size() == 1) {
//                //
//                appendRule("jump", getCode(incomingBranches.get(0)), getCode(to), getCode(new JumpDest("BLACKHOLE"))    );
//            } else {
            Instruction lastJoinInstruction = incomingBranches.get(0);
            for (int i = 1; i < incomingBranches.size() - 1; ++i) {
                Instruction tmpJoinInstruction = new JumpDest(to.toString() + "_tmp_" + i);
                appendRule("join", getCode(lastJoinInstruction),
                        getCode(incomingBranches.get(i)),
                        getCode(tmpJoinInstruction));
                lastJoinInstruction = tmpJoinInstruction;
            }
            appendRule("join", getCode(lastJoinInstruction),
                    getCode(incomingBranches.get(incomingBranches.size()-1)),
                    getCode(to));
//            }
        }
    }

    protected void deriveIfPredicates() { //ok, we create the taint rules in addition, but they are needed only in the mayFollow
        log(">> Derive TaintElse and TaintThen predicates <<");
        for (Instruction instr : instructions) {
            if (instr instanceof JumpI) {
                JumpI ifInstr = (JumpI) instr;
                Variable condition = ifInstr.getCondition();
                Instruction thenInstr = ifInstr.getTargetInstruction();
                Instruction elseInstr = ifInstr.getNext();
                Instruction mergeInstr = ifInstr.getMergeInstruction();

                if (thenInstr != null && thenInstr != mergeInstr) {
                    log("then instruction: " + thenInstr.getStringRepresentation());
                    createTaintRule(instr, thenInstr, condition);
                }

                if (elseInstr != null && elseInstr != mergeInstr ) {
                    log("else instruction: " + elseInstr.getStringRepresentation());
                    createTaintRule(instr, elseInstr, condition);
                }

                if (mergeInstr != null) {
                    log("merge instruction: " + mergeInstr.getStringRepresentation());
                    createEndIfRule(instr, mergeInstr);
                }

                createGotoRule(instr, condition, elseInstr); //todo: check if this is ok, especially with
                //validated arguments pattern
            }
        }
    }

    private void createGotoRule(Instruction instr, Variable condition, Instruction elseBranch) {
        appendRule("goto", getCode(instr), getCode(condition), getCode(elseBranch));
    }

    private void createTaintRule(Instruction labStart, Instruction lab, Variable var) { //ok
        appendRule("taint", getCode(labStart), getCode(lab), getCode(var));
    }

    protected void createCallRule(Instruction instr) { //ok, I wrote it
        Variable returnVar = ((Call) instr).getReturnVar();
        Variable amount = ((Call) instr).getAmount();
        appendRule("call", getCode(instr), getCode(returnVar), getCode(((Call) instr).getGas()), getCode(amount));
    }

    protected void createSLoadRule(Instruction instr, Variable index, Variable var) { //OK
        appendRule("sloadInstr", getCode(instr), getCode(index), getCode(var));
    }

    protected void createMLoadRule(Instruction instr, Variable offset, Variable var) { //OK
        appendRule("mloadInstr", getCode(instr), getCode(offset), getCode(var));
    }

    public List<DSLPatternResult> getResults() throws IOException {
        List<String> pattNames = getPatternNames();
        List<DSLPatternResult> results = new ArrayList<>(pattNames.size());

        for (String name : pattNames) {
            results.add(readResults(name));
        }

        return results;
    }

    private DSLPatternResult readResults(String pattName) throws IOException {
        Set<Integer> complIntInstr = readResFromFile(WORKSPACE_OUT + "/" + pattName + "Compliance.csv");
        Set<Integer> violIntInstr = readResFromFile(WORKSPACE_OUT + "/" + pattName + "Violation.csv");
        Set<Integer> warningsIntInstr = readResFromFile(WORKSPACE_OUT + "/" + pattName + "Warnings.csv");

        BiMap<Integer, Instruction> invMap = instrToCode.inverse();

        Set<Instruction> complInstr = new HashSet<>(complIntInstr.size());
        complIntInstr.forEach((integer -> complInstr.add(invMap.get(integer))));

        Set<Instruction> violInstr = new HashSet<>(violIntInstr.size());
        violIntInstr.forEach((integer -> violInstr.add(invMap.get(integer))));

        Set<Instruction> warningsInstr = new HashSet<>(warningsIntInstr.size());
        warningsIntInstr.forEach((integer -> warningsInstr.add(invMap.get(integer))));

        Set<Instruction> intersection = new HashSet<>(violInstr);
        intersection.retainAll(complInstr);

        DSLPatternResult res = new DSLPatternResult(pattName,
                violInstr,
                warningsInstr,
                complInstr,
                intersection
                );


        return res;
    }

    private Set<Integer> readResFromFile(String filename) throws IOException {
        Reader in = new FileReader(filename);
        Iterable<CSVRecord> records = CSVFormat.TDF.parse(in);
        Set<Integer> entries = new HashSet<>();

        for (CSVRecord record : records) {
            entries.add(Integer.parseInt(record.get(0)));
        }
        in.close();
        return entries;
    }

    private List<String> getPatternNames() throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(DSLPatternsCompiler.PATTERN_NAMES_CSV));
        String line = br.readLine();
        if(line != null) {
            // use comma as separator
            String[] patternNames = line.split(" , ");

            return Arrays.asList(patternNames);
        }
        else
            throw new IOException("File found, but empty");
    }
}
package za.ac.sun.cs.coastal.symbolic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;

import za.ac.sun.cs.coastal.Configuration;
import za.ac.sun.cs.coastal.Configuration.Trigger;
import za.ac.sun.cs.coastal.instrument.Bytecodes;
import za.ac.sun.cs.coastal.listener.InstructionListener;
import za.ac.sun.cs.green.expr.Constant;
import za.ac.sun.cs.green.expr.Expression;
import za.ac.sun.cs.green.expr.IntConstant;
import za.ac.sun.cs.green.expr.IntVariable;
import za.ac.sun.cs.green.expr.Operation;
import za.ac.sun.cs.green.expr.Operation.Operator;

public class SymbolicState {

	private static final String FIELD_SEPARATOR = "/";

	private static final String INDEX_SEPARATOR = "_D_"; // "$"

	public static final String CHAR_SEPARATOR = "_H_"; // "#"

	public static final String NEW_VAR_PREFIX = "U_D_"; // "$"

	private static Configuration configuration;

	private static Logger log;

	private static boolean dumpFrame;

	private static long limitConjuncts;

	// if true, check for limit on conjuncts
	private static boolean dangerFlag = false;

	private static boolean symbolicMode = false;

	private static Stack<SymbolicFrame> frames = new Stack<>();

	private static int objectIdCount = 0;

	private static int newVariableCount = 0;

	private static Map<String, Expression> instanceData = new HashMap<>();

	private static Stack<Expression> args = new Stack<>();

	private static SegmentedPC spc = null;

	private static Expression pendingExtraConjunct = null;

	private static boolean isPreviousConstant = false;

	private static boolean isPreviousDuplicate = false;

	private static Set<String> conjunctSet = new HashSet<>();

	private static Map<String, Constant> concreteValues = null;

	private static boolean mayContinue = true;

	private static Map<String, Integer> markers = new HashMap<>();

	private static Stack<Expression> pendingSwitch = new Stack<>();

	public static void initialize(Configuration configuration) {
		SymbolicState.configuration = configuration;
		log = configuration.getLog();
		dumpFrame = configuration.getDumpFrame();
		limitConjuncts = configuration.getLimitConjuncts();
		if (limitConjuncts == 0) {
			limitConjuncts = Long.MAX_VALUE;
		}
	}

	public static void reset(Map<String, Constant> concreteValues) {
		dangerFlag = false;
		symbolicMode = false;
		frames.clear();
		objectIdCount = 0;
		// newVariableCount must NOT be reset
		instanceData.clear();
		args.clear();
		spc = null;
		pendingExtraConjunct = null;
		conjunctSet.clear();
		SymbolicState.concreteValues = concreteValues;
	}

	public static boolean getSymbolicMode() {
		return symbolicMode;
	}

	public static boolean mayContinue() {
		return mayContinue;
	}

	public static Map<String, Integer> getMarkers() {
		return markers;
	}

	public static SegmentedPC getSegmentedPathCondition() {
		return spc;
	}

	public static void push(Expression expr) {
		frames.peek().push(expr);
	}

	public static Expression pop() {
		return frames.peek().pop();
	}

	private static Expression peek() {
		return frames.peek().peek();
	}

	private static Expression getLocal(int index) {
		return frames.peek().getLocal(index);
	}

	private static void setLocal(int index, Expression value) {
		frames.peek().setLocal(index, value);
	}

	private static void putField(int objectId, String fieldName, Expression value) {
		putField(Integer.toString(objectId), fieldName, value);
	}

	private static void putField(String objectName, String fieldName, Expression value) {
		String fullFieldName = objectName + FIELD_SEPARATOR + fieldName;
		instanceData.put(fullFieldName, value);
	}
	
	public static Expression getField(int objectId, String fieldName) {
		return getField(Integer.toString(objectId), fieldName);
	}

	public static Expression getField(String objectName, String fieldName) {
		String fullFieldName = objectName + FIELD_SEPARATOR + fieldName;
		Expression value = instanceData.get(fullFieldName);
		if (value == null) {
			int min = configuration.getDefaultMinIntValue();
			int max = configuration.getDefaultMaxIntValue();
			value = new IntVariable(getNewVariableName(), min, max);
			instanceData.put(fullFieldName, value);
		}
		return value;
	}
	
	// Arrays are just objects
	public static int createArray() {
		return incrAndGetNewObjectId();
	}

	public static int getArrayLength(int arrayId) {
		return ((IntConstant) getField(arrayId, "length")).getValue();
	}

	public static void setArrayLength(int arrayId, int length) {
		putField(arrayId, "length", new IntConstant(length));
	}

	public static Expression getArrayValue(int arrayId, int index) {
		return getField(arrayId, "" + index);
	}

	private static void setArrayValue(int arrayId, int index, Expression value) {
		putField(arrayId, "" + index, value);
	}

	// Strings are just objects
	public static int createString() {
		return incrAndGetNewObjectId();
	}

	public static Expression getStringLength(int stringId) {
		return getField(stringId, "length");
	}

	public static void setStringLength(int stringId, Expression length) {
		putField(stringId, "length", length);
	}

	public static Expression getStringChar(int stringId, int index) {
		return getField(stringId, "" + index);
	}

	public static void setStringChar(int stringId, int index, Expression value) {
		putField(stringId, "" + index, value);
	}

	private static void pushConjunct(Expression conjunct) {
		String c = conjunct.toString();
		/*
		 * Set "isPreviousConstant" and "isPreviousDuplicate" so that if we
		 * encounter the corresponding else, we know to ignore it.
		 */
		isPreviousConstant = isConstant(conjunct);
		isPreviousDuplicate = false;
		if (isPreviousConstant) {
			log.trace(">>> constant conjunct ignored: {}", c);
		} else if (conjunctSet.add(c)) {
			spc = new SegmentedPCIf(spc, conjunct, pendingExtraConjunct, true);
			pendingExtraConjunct = null;
			log.trace(">>> adding conjunct: {}", c);
			log.trace(">>> spc is now: {}", spc.getPathCondition().toString());
		} else {
			log.trace(">>> duplicate conjunct ignored: {}", c);
			isPreviousDuplicate = true;
		}
	}

	private static void pushConjunct(Expression expression, int min, int max, int cur) {
		Operation conjunct;
		if (cur < min) {
			Operation lo = new Operation(Operator.LT, expression, new IntConstant(min));
			Operation hi = new Operation(Operator.GT, expression, new IntConstant(max));
			conjunct = new Operation(Operator.OR, lo, hi);
		} else {
			conjunct = new Operation(Operator.EQ, expression, new IntConstant(cur));
		}
		String c = conjunct.toString();
		if (isConstant(conjunct)) {
			log.trace(">>> constant (switch) conjunct ignored: {}", c);
		} else if (conjunctSet.add(c)) {
			spc = new SegmentedPCSwitch(spc, expression, pendingExtraConjunct, min, max, cur);
			pendingExtraConjunct = null;
			log.trace(">>> adding (switch) conjunct: {}", c);
			log.trace(">>> spc is now: {}", spc.getPathCondition().toString());
		} else {
			log.trace(">>> duplicate (switch) conjunct ignored: {}", c);
		}
	}

	public static void pushExtraConjunct(Expression extraConjunct) {
		if (!isConstant(extraConjunct)) {
			if (pendingExtraConjunct == null) {
				pendingExtraConjunct = extraConjunct;
			} else {
				pendingExtraConjunct = new Operation(Operator.AND, extraConjunct, pendingExtraConjunct);
			}
		}
	}

	private static boolean methodReturn() {
		assert symbolicMode;
		assert !frames.isEmpty();
		int methodNumber = frames.pop().getMethodNumber();
		if (frames.isEmpty()) {
			log.trace(">>> symbolic mode switched off");
			symbolicMode = false;
		}
		notifyExitMethod(methodNumber);
		return symbolicMode;
	}

	private static int incrAndGetNewObjectId() {
		return ++objectIdCount;
	}

	public static String getNewVariableName() {
		return NEW_VAR_PREFIX + newVariableCount++;
	}

	private static void dumpFrames() {
		int n = frames.size();
		for (int i = n - 1; i >= 0; i--) {
			log.trace("--> st{} locals:{}", frames.get(i).stack, frames.get(i).locals);
		}
		log.trace("--> data:{}", instanceData);
	}

	private static final Class<?>[] EMPTY_PARAMETERS = new Class<?>[0];

	private static final Object[] EMPTY_ARGUMENTS = new Object[0];

	private static boolean executeDelegate(String owner, String name, String descriptor) {
		Object delegate = configuration.findDelegate(owner);
		if (delegate == null) {
			return false;
		}
		String methodName = name + getAsciiSignature(descriptor);
		Method delegateMethod = null;
		try {
			delegateMethod = delegate.getClass().getDeclaredMethod(methodName, EMPTY_PARAMETERS);
		} catch (NoSuchMethodException | SecurityException e) {
			log.trace("@@@ no delegate: {}", methodName);
			return false;
		}
		assert delegateMethod != null;
		log.trace("@@@ found delegate: {}", methodName);
		try {
			if ((boolean) delegateMethod.invoke(delegate, EMPTY_ARGUMENTS)) {
				return true;
			}
		} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException x) {
			// This should never happen!
			x.printStackTrace();
		}
		return false;
	}

	// ======================================================================
	//
	// SYMBOLIC INTERACTION
	//
	// ======================================================================

	public static void stop() {
		if (configuration.getObeyStops()) {
			mayContinue = false;
			log.info("!!! PROGRAM TERMINATION POINT REACHED");
		}
	}

	public static void stop(String message) {
		if (configuration.getObeyStops()) {
			mayContinue = false;
			log.info("!!! PROGRAM TERMINATION POINT REACHED");
			log.info("!!! {}", message);
		}
	}

	public static void mark(int marker) {
		if (configuration.getRecordMarks()) {
			mark(Integer.toString(marker));
		}
	}

	public static void mark(String marker) {
		if (configuration.getRecordMarks()) {
			String key = marker;
			Integer n = markers.get(key);
			if (n == null) {
				markers.put(key, 1);
			} else {
				markers.put(key, n + 1);
			}
		}
	}

	// ======================================================================
	//
	// INSTRUCTIONS
	//
	// ======================================================================

	public static int getConcreteInt(int triggerIndex, int index, int address, int currentValue) {
		Trigger trigger = configuration.getTrigger(triggerIndex);
		String name = trigger.getParamName(index);
		if (name == null) { // not symbolic
			setLocal(address, new IntConstant(currentValue));
			return currentValue;
		}
		int min = configuration.getMinBound(name);
		int max = configuration.getMaxBound(name);
		setLocal(address, new IntVariable(name, min, max));
		IntConstant concrete = (IntConstant) (concreteValues == null ? null : concreteValues.get(name));
		return (concrete == null) ? currentValue : concrete.getValue();
	}

	public static char getConcreteChar(int triggerIndex, int index, int address, char currentValue) {
		Trigger trigger = configuration.getTrigger(triggerIndex);
		String name = trigger.getParamName(index);
		if (name == null) { // not symbolic
			setLocal(address, new IntConstant(currentValue));
			return currentValue;
		}
		int min = configuration.getMinBound(name);
		int max = configuration.getMaxBound(name);
		setLocal(address, new IntVariable(name, min, max));
		IntConstant concrete = (IntConstant) (concreteValues == null ? null : concreteValues.get(name));
		return (concrete == null) ? currentValue : (char) concrete.getValue();
	}

	public static String getConcreteString(int triggerIndex, int index, int address, String currentValue) {
		Trigger trigger = configuration.getTrigger(triggerIndex);
		String name = trigger.getParamName(index);
		int length = currentValue.length();
		int stringId = createString();
		setStringLength(stringId, new IntConstant(length));
		if (name == null) { // not symbolic
			for (int i = 0; i < length; i++) {
				IntConstant chValue = new IntConstant(currentValue.charAt(i));
				setStringChar(stringId, i, chValue);
			}
			setLocal(address, new IntConstant(stringId));
			return currentValue;
		} else {
			char[] chars = new char[length];
			currentValue.getChars(0, length, chars, 0); // copy string into chars[]
			for (int i = 0; i < length; i++) {
				String entryName = name + CHAR_SEPARATOR + i;
				Constant concrete = ((name == null) || (concreteValues == null)) ? null : concreteValues.get(entryName);
				Expression entryExpr = new IntVariable(entryName, 0, 255);
				if ((concrete != null) && (concrete instanceof IntConstant)) {
					chars[i] = (char) ((IntConstant) concrete).getValue();
				}
				setArrayValue(stringId, i, entryExpr);
			}
			setLocal(address, new IntConstant(stringId));
			return new String(chars);
		}
	}

	public static int[] getConcreteIntArray(int triggerIndex, int index, int address, int[] currentValue) {
		Trigger trigger = configuration.getTrigger(triggerIndex);
		String name = trigger.getParamName(index);
		int length = currentValue.length;
		int arrayId = createArray();
		setArrayLength(arrayId, length);
		int[] value;
		if (name == null) { // not symbolic
			value = currentValue;
			for (int i = 0; i < length; i++) {
				setArrayValue(arrayId, i, new IntConstant(value[i]));
			}
		} else {
			value = new int[length];
			for (int i = 0; i < length; i++) {
				String entryName = name + INDEX_SEPARATOR + i;
				Constant concrete = ((name == null) || (concreteValues == null)) ? null : concreteValues.get(entryName);
				if ((concrete != null) && (concrete instanceof IntConstant)) {
					value[i] = ((IntConstant) concrete).getValue();
				} else {
					value[i] = currentValue[i];
				}
				int min = configuration.getMinBound(entryName, name);
				int max = configuration.getMaxBound(entryName, name);
				Expression entryExpr = new IntVariable(entryName, min, max);
				setArrayValue(arrayId, i, entryExpr);
			}
		}
		setLocal(index, new IntConstant(arrayId));
		return value;
	}

	public static void triggerMethod(int methodNumber) {
		if (!symbolicMode) {
			log.trace(">>> symbolic mode switched on");
			symbolicMode = true;
			frames.push(new SymbolicFrame(methodNumber));
			if (dumpFrame) {
				dumpFrames();
			}
		}
		notifyEnterMethod(methodNumber);
	}

	public static void startMethod(int methodNumber, int argCount) {
		if (!symbolicMode) {
			return;
		}
		log.trace(">>> transferring arguments");
		assert args.isEmpty();
		for (int i = 0; i < argCount; i++) {
			args.push(pop());
		}
		frames.push(new SymbolicFrame(methodNumber));
		for (int i = 0; i < argCount; i++) {
			setLocal(i, args.pop());
		}
		if (dumpFrame) {
			dumpFrames();
		}
		notifyEnterMethod(methodNumber);
	}

	public static void linenumber(int instr, int line) {
		if (!symbolicMode) {
			return;
		}
		log.trace("### LINENUMBER {}", line);
		notifyLinenumber(instr, line);
	}

	public static void insn(int instr, int opcode) throws LimitConjunctException {
		if (!symbolicMode) {
			return;
		}
		log.trace("{}", () -> Bytecodes.toString(opcode));
		if (dangerFlag) {
			checkLimitConjuncts();
		}
		notifyInsn(instr, opcode);
		switch (opcode) {
		case Opcodes.ACONST_NULL:
			push(Operation.ZERO);
			break;
		case Opcodes.ICONST_M1:
			push(new IntConstant(-1));
			break;
		case Opcodes.ICONST_0:
			push(Operation.ZERO);
			break;
		case Opcodes.ICONST_1:
			push(Operation.ONE);
			break;
		case Opcodes.ICONST_2:
			push(new IntConstant(2));
			break;
		case Opcodes.ICONST_3:
			push(new IntConstant(3));
			break;
		case Opcodes.ICONST_4:
			push(new IntConstant(4));
			break;
		case Opcodes.ICONST_5:
			push(new IntConstant(5));
			break;
		case Opcodes.IALOAD:
			int i = ((IntConstant) pop()).getValue();
			int a = ((IntConstant) pop()).getValue();
			push(getArrayValue(a, i));
			break;
		case Opcodes.IASTORE:
			Expression e = pop();
			i = ((IntConstant) pop()).getValue();
			a = ((IntConstant) pop()).getValue();
			setArrayValue(a, i, e);
			break;
		case Opcodes.POP:
			pop();
			break;
		case Opcodes.DUP:
			push(peek());
			break;
		case Opcodes.IADD:
			e = pop();
			if (e instanceof IntConstant) {
				Expression f = pop();
				if (f instanceof IntConstant) {
					push(new IntConstant(((IntConstant) f).getValue() + ((IntConstant) e).getValue()));
				} else {
					push(new Operation(Operator.ADD, f, e));
				}
			} else {
				push(new Operation(Operator.ADD, pop(), e));
			}
			break;
		case Opcodes.IMUL:
			e = pop();
			push(new Operation(Operator.MUL, pop(), e));
			break;
		case Opcodes.ISUB:
			e = pop();
			if (e instanceof IntConstant) {
				Expression f = pop();
				if (f instanceof IntConstant) {
					push(new IntConstant(((IntConstant) f).getValue() - ((IntConstant) e).getValue()));
				} else {
					push(new Operation(Operator.SUB, f, e));
				}
			} else {
				push(new Operation(Operator.SUB, pop(), e));
			}
			break;
		case Opcodes.IRETURN:
			e = pop();
			if (methodReturn()) {
				push(e);
			}
			break;
		case Opcodes.ARETURN:
			e = pop();
			if (methodReturn()) {
				push(e);
			}
			break;
		case Opcodes.RETURN:
			methodReturn();
			break;
		case Opcodes.ARRAYLENGTH:
			int id = ((IntConstant) pop()).getValue();
			push(getField(id, "length"));
			break;
		default:
			log.fatal("UNIMPLEMENTED INSTRUCTION: {} (opcode: {})", Bytecodes.toString(opcode), opcode);
			System.exit(1);
		}
		if (dumpFrame) {
			dumpFrames();
		}
	}

	public static void intInsn(int instr, int opcode, int operand) throws LimitConjunctException {
		if (!symbolicMode) {
			return;
		}
		log.trace("{} {}", Bytecodes.toString(opcode), operand);
		if (dangerFlag) {
			checkLimitConjuncts();
		}
		notifyIntInsn(instr, opcode, operand);
		switch (opcode) {
		case Opcodes.BIPUSH:
			push(new IntConstant(operand));
			break;
		case Opcodes.SIPUSH:
			push(new IntConstant(operand));
			break;
		case Opcodes.NEWARRAY:
			assert operand == Opcodes.T_INT;
			Expression e = pop();
			int n = ((IntConstant) e).getValue();
			int id = createArray();
			setArrayLength(id, n);
			for (int i = 0; i < n; i++) {
				setArrayValue(id, i, Operation.ZERO);
			}
			push(new IntConstant(id));
			break;
		default:
			log.fatal("UNIMPLEMENTED INSTRUCTION: {} {} (opcode: {})", Bytecodes.toString(opcode), operand, opcode);
			System.exit(1);
		}
		if (dumpFrame) {
			dumpFrames();
		}
	}

	public static void varInsn(int instr, int opcode, int var) throws LimitConjunctException {
		if (!symbolicMode) {
			return;
		}
		log.trace("{} {}", Bytecodes.toString(opcode), var);
		if (dangerFlag) {
			checkLimitConjuncts();
		}
		notifyVarInsn(instr, opcode, var);
		switch (opcode) {
		case Opcodes.ALOAD:
			push(getLocal(var));
			break;
		case Opcodes.ILOAD:
			push(getLocal(var));
			break;
		case Opcodes.ASTORE:
			setLocal(var, pop());
			break;
		case Opcodes.ISTORE:
			setLocal(var, pop());
			break;
		default:
			log.fatal("UNIMPLEMENTED INSTRUCTION: {} (opcode: {})", Bytecodes.toString(opcode), opcode);
			System.exit(1);
		}
		if (dumpFrame) {
			dumpFrames();
		}
	}

	public static void typeInsn(int instr, int opcode) throws LimitConjunctException {
		if (!symbolicMode) {
			return;
		}
		log.trace("{}", Bytecodes.toString(opcode));
		if (dangerFlag) {
			checkLimitConjuncts();
		}
		notifyTypeInsn(instr, opcode);
		switch (opcode) {
		case Opcodes.NEW:
			int id = incrAndGetNewObjectId();
			push(new IntConstant(id));
			break;
		default:
			log.fatal("UNIMPLEMENTED INSTRUCTION: {} (opcode: {})", Bytecodes.toString(opcode), opcode);
			System.exit(1);
		}
		if (dumpFrame) {
			dumpFrames();
		}
	}

	public static void fieldInsn(int instr, int opcode, String owner, String name, String descriptor)
			throws LimitConjunctException {
		if (!symbolicMode) {
			return;
		}
		log.trace("{} {} {} {}", Bytecodes.toString(opcode), owner, name, descriptor);
		if (dangerFlag) {
			checkLimitConjuncts();
		}
		notifyFieldInsn(instr, opcode, owner, name, descriptor);
		switch (opcode) {
		case Opcodes.GETSTATIC:
			push(getField(owner, name));
			break;
		case Opcodes.PUTSTATIC:
			Expression e = pop();
			putField(owner, name, e);
			break;
		case Opcodes.GETFIELD:
			int id = ((IntConstant) pop()).getValue();
			push(getField(id, name));
			break;
		case Opcodes.PUTFIELD:
			e = pop();
			id = ((IntConstant) pop()).getValue();
			putField(id, name, e);
			break;
		default:
			log.fatal("UNIMPLEMENTED INSTRUCTION: {} {} {} {} (opcode: {})", Bytecodes.toString(opcode), owner, name,
					descriptor, opcode);
			System.exit(1);
		}
		if (dumpFrame) {
			dumpFrames();
		}
	}

	public static void methodInsn(int instr, int opcode, String owner, String name, String descriptor)
			throws LimitConjunctException {
		if (!symbolicMode) {
			return;
		}
		log.trace("{} {} {} {}", Bytecodes.toString(opcode), owner, name, descriptor);
		if (dangerFlag) {
			checkLimitConjuncts();
		}
		notifyMethodInsn(instr, opcode, owner, name, descriptor);
		switch (opcode) {
		case Opcodes.INVOKESPECIAL:
		case Opcodes.INVOKEVIRTUAL:
			String className = owner.replace('/', '.');
			if (!configuration.isTarget(className)) {
				if (!executeDelegate(className, name, descriptor)) {
					// get rid of arguments
					int n = 1 + getArgumentCount(descriptor);
					while (n-- > 0) {
						pop();
					}
					// insert return type on stack
					char typeCh = getReturnType(descriptor).charAt(0);
					if ((typeCh == 'I') || (typeCh == 'Z')) {
						push(new IntVariable(getNewVariableName(), -1000, 1000));
					} else if ((typeCh != 'V') && (typeCh != '?')) {
						push(Operation.ZERO);
					}
				}
			}
			break;
		case Opcodes.INVOKESTATIC:
			className = owner.replace('/', '.');
			if (!configuration.isTarget(className)) {
				if (!executeDelegate(className, name, descriptor)) {
					// get rid of arguments
					int n = getArgumentCount(descriptor);
					while (n-- > 0) {
						pop();
					}
					// insert return type on stack
					char typeCh = getReturnType(descriptor).charAt(0);
					if ((typeCh == 'I') || (typeCh == 'Z')) {
						push(new IntVariable(getNewVariableName(), -1000, 1000));
					} else if ((typeCh != 'V') && (typeCh != '?')) {
						push(Operation.ZERO);
					}
				}
			}
			break;
		default:
			log.fatal("UNIMPLEMENTED INSTRUCTION: {} {} {} {} (opcode: {})", Bytecodes.toString(opcode), owner, name,
					descriptor, opcode);
			System.exit(1);
		}
		if (dumpFrame) {
			dumpFrames();
		}
	}

	public static void invokeDynamicInsn(int instr, int opcode) throws LimitConjunctException {
		if (!symbolicMode) {
			return;
		}
		log.trace("{}", Bytecodes.toString(opcode));
		if (dangerFlag) {
			checkLimitConjuncts();
		}
		notifyInvokeDynamicInsn(instr, opcode);
		switch (opcode) {
		default:
			log.fatal("UNIMPLEMENTED INSTRUCTION: {} (opcode: {})", Bytecodes.toString(opcode), opcode);
			System.exit(1);
		}
		if (dumpFrame) {
			dumpFrames();
		}
	}

	/* Missing offset because destination not yet known. */
	public static void jumpInsn(int instr, int opcode) throws LimitConjunctException {
		if (!symbolicMode) {
			return;
		}
		log.trace("{}", Bytecodes.toString(opcode));
		if (dangerFlag) {
			checkLimitConjuncts();
		}
		notifyJumpInsn(instr, opcode);
		dangerFlag = true;
		switch (opcode) {
		case Opcodes.GOTO:
			// do nothing
			break;
		case Opcodes.IFEQ:
			Expression e = pop();
			pushConjunct(new Operation(Operator.EQ, e, Operation.ZERO));
			break;
		case Opcodes.IFNE:
			e = pop();
			pushConjunct(new Operation(Operator.NE, e, Operation.ZERO));
			break;
		case Opcodes.IFLT:
			e = pop();
			pushConjunct(new Operation(Operator.LT, e, Operation.ZERO));
			break;
		case Opcodes.IFGE:
			e = pop();
			pushConjunct(new Operation(Operator.GE, e, Operation.ZERO));
			break;
		case Opcodes.IFGT:
			e = pop();
			pushConjunct(new Operation(Operator.GT, e, Operation.ZERO));
			break;
		case Opcodes.IFLE:
			e = pop();
			pushConjunct(new Operation(Operator.LE, e, Operation.ZERO));
			break;
		case Opcodes.IF_ICMPEQ:
			e = pop();
			pushConjunct(new Operation(Operator.EQ, pop(), e));
			break;
		case Opcodes.IF_ICMPNE:
			e = pop();
			pushConjunct(new Operation(Operator.NE, pop(), e));
			break;
		case Opcodes.IF_ICMPLT:
			e = pop();
			pushConjunct(new Operation(Operator.LT, pop(), e));
			break;
		case Opcodes.IF_ICMPGE:
			e = pop();
			pushConjunct(new Operation(Operator.GE, pop(), e));
			break;
		case Opcodes.IF_ICMPGT:
			e = pop();
			pushConjunct(new Operation(Operator.GT, pop(), e));
			break;
		case Opcodes.IF_ICMPLE:
			e = pop();
			pushConjunct(new Operation(Operator.LE, pop(), e));
			break;
		case Opcodes.IF_ACMPEQ:
			e = pop();
			pushConjunct(new Operation(Operator.EQ, pop(), e));
			break;
		case Opcodes.IF_ACMPNE:
			e = pop();
			pushConjunct(new Operation(Operator.NE, pop(), e));
			break;
		case Opcodes.IFNULL:
			e = pop();
			pushConjunct(new Operation(Operator.EQ, e, Operation.ZERO));
			break;
		case Opcodes.IFNONNULL:
			e = pop();
			pushConjunct(new Operation(Operator.NE, e, Operation.ZERO));
			break;
		default:
			log.fatal("UNIMPLEMENTED INSTRUCTION: {} (opcode: {})", Bytecodes.toString(opcode), opcode);
			System.exit(1);
		}
		if (dumpFrame) {
			dumpFrames();
		}
	}

	public static void postJumpInsn(int instr, int opcode) throws LimitConjunctException {
		if (!symbolicMode) {
			return;
		}
		log.trace("(POST) {}", Bytecodes.toString(opcode));
		if (!isPreviousConstant && !isPreviousDuplicate) {
			log.trace(">>> previous conjunct is false");
			notifyPostJumpInsn(instr, opcode);
			assert spc instanceof SegmentedPCIf;
			spc = ((SegmentedPCIf) spc).negate();
			checkLimitConjuncts();
			log.trace(">>> spc is now: {}", spc.getPathCondition().toString());
		}
	}

	private static void checkLimitConjuncts() throws LimitConjunctException {
		if ((spc != null) && (spc.getDepth() >= limitConjuncts)) {
			throw new LimitConjunctException();
		}
		dangerFlag = false;
	}

	public static void ldcInsn(int instr, int opcode, Object value) throws LimitConjunctException {
		if (!symbolicMode) {
			return;
		}
		log.trace("{} {}", Bytecodes.toString(opcode), value);
		if (dangerFlag) {
			checkLimitConjuncts();
		}
		notifyLdcInsn(instr, opcode, value);
		switch (opcode) {
		case Opcodes.LDC:
			if (value instanceof Integer) {
				push(new IntConstant((int) value));
			} else if (value instanceof String) {
				String s = (String) value;
				int id = createArray();
				putField(id, "length", new IntConstant(s.length()));
				for (int i = 0; i < s.length(); i++) {
					setArrayValue(id, i, new IntConstant(s.charAt(i)));
				}
				push(new IntConstant(id));
			} else {
				push(Operation.ZERO);
			}
			break;
		default:
			log.fatal("UNIMPLEMENTED INSTRUCTION: {} (opcode: {})", Bytecodes.toString(opcode), opcode);
			System.exit(1);
		}
		if (dumpFrame) {
			dumpFrames();
		}
	}

	public static void iincInsn(int instr, int var, int increment) throws LimitConjunctException {
		final int opcode = 132;
		if (!symbolicMode) {
			return;
		}
		log.trace("{} {}", Bytecodes.toString(opcode), increment);
		if (dangerFlag) {
			checkLimitConjuncts();
		}
		notifyIincInsn(instr, var, increment);
		Expression e0 = getLocal(var);
		Expression e1 = new IntConstant(increment);
		setLocal(var, Operation.apply(Operator.ADD, e0, e1));
		if (dumpFrame) {
			dumpFrames();
		}
	}

	public static void tableSwitchInsn(int instr, int opcode) throws LimitConjunctException {
		if (!symbolicMode) {
			return;
		}
		log.trace("{}", Bytecodes.toString(opcode));
		if (dangerFlag) {
			checkLimitConjuncts();
		}
		notifyTableSwitchInsn(instr, opcode);
		pendingSwitch.push(pop());
		if (dumpFrame) {
			dumpFrames();
		}
	}

	public static void tableCaseInsn(int min, int max, int value) throws LimitConjunctException {
		if (!symbolicMode) {
			return;
		}
		log.trace("CASE FOR {}", Bytecodes.toString(Opcodes.TABLESWITCH));
		if (dangerFlag) {
			checkLimitConjuncts();
		}
		if (!pendingSwitch.isEmpty()) {
			pushConjunct(pendingSwitch.pop(), min, max, value);
		}
		if (dumpFrame) {
			dumpFrames();
		}
	}

	public static void lookupSwitchInsn(int instr, int opcode) throws LimitConjunctException {
		if (!symbolicMode) {
			return;
		}
		log.trace("{}", Bytecodes.toString(opcode));
		if (dangerFlag) {
			checkLimitConjuncts();
		}
		notifyLookupSwitchInsn(instr, opcode);
		switch (opcode) {
		default:
			log.fatal("UNIMPLEMENTED INSTRUCTION: {} (opcode: {})", Bytecodes.toString(opcode), opcode);
			System.exit(1);
		}
		if (dumpFrame) {
			dumpFrames();
		}
	}

	public static void multiANewArrayInsn(int instr, int opcode) throws LimitConjunctException {
		if (!symbolicMode) {
			return;
		}
		log.trace("{}", Bytecodes.toString(opcode));
		if (dangerFlag) {
			checkLimitConjuncts();
		}
		notifyMultiANewArrayInsn(instr, opcode);
		switch (opcode) {
		default:
			log.fatal("UNIMPLEMENTED INSTRUCTION: {} (opcode: {})", Bytecodes.toString(opcode), opcode);
			System.exit(1);
		}
		if (dumpFrame) {
			dumpFrames();
		}
	}

	// ======================================================================
	//
	// LISTENERS
	//
	// ======================================================================

	private static List<InstructionListener> instructionListeners = new ArrayList<>();

	public static void registerListener(InstructionListener listener) {
		instructionListeners.add(listener);
	}

	private static void notifyEnterMethod(int methodNumber) {
		for (InstructionListener listener : instructionListeners) {
			listener.enterMethod(methodNumber);
		}
	}

	private static void notifyExitMethod(int methodNumber) {
		for (InstructionListener listener : instructionListeners) {
			listener.exitMethod(methodNumber);
		}
	}

	private static void notifyLinenumber(int instr, int opcode) {
		for (InstructionListener listener : instructionListeners) {
			listener.linenumber(instr, opcode);
		}
	}

	private static void notifyInsn(int instr, int opcode) {
		for (InstructionListener listener : instructionListeners) {
			listener.insn(instr, opcode);
		}
	}

	private static void notifyIntInsn(int instr, int opcode, int operand) {
		for (InstructionListener listener : instructionListeners) {
			listener.intInsn(instr, opcode, operand);
		}
	}

	private static void notifyVarInsn(int instr, int opcode, int var) {
		for (InstructionListener listener : instructionListeners) {
			listener.varInsn(instr, opcode, var);
		}
	}

	private static void notifyTypeInsn(int instr, int opcode) {
		for (InstructionListener listener : instructionListeners) {
			listener.typeInsn(instr, opcode);
		}
	}

	private static void notifyFieldInsn(int instr, int opcode, String owner, String name, String descriptor) {
		for (InstructionListener listener : instructionListeners) {
			listener.fieldInsn(instr, opcode, owner, name, descriptor);
		}
	}

	private static void notifyMethodInsn(int instr, int opcode, String owner, String name, String descriptor) {
		for (InstructionListener listener : instructionListeners) {
			listener.methodInsn(instr, opcode, owner, name, descriptor);
		}
	}

	private static void notifyInvokeDynamicInsn(int instr, int opcode) {
		for (InstructionListener listener : instructionListeners) {
			listener.invokeDynamicInsn(instr, opcode);
		}
	}

	private static void notifyJumpInsn(int instr, int opcode) {
		for (InstructionListener listener : instructionListeners) {
			listener.jumpInsn(instr, opcode);
		}
	}

	private static void notifyPostJumpInsn(int instr, int opcode) {
		for (InstructionListener listener : instructionListeners) {
			listener.postJumpInsn(instr, opcode);
		}
	}

	private static void notifyLdcInsn(int instr, int opcode, Object value) {
		for (InstructionListener listener : instructionListeners) {
			listener.ldcInsn(instr, opcode, value);
		}
	}

	private static void notifyIincInsn(int instr, int var, int increment) {
		for (InstructionListener listener : instructionListeners) {
			listener.iincInsn(instr, var, increment);
		}
	}

	private static void notifyTableSwitchInsn(int instr, int opcode) {
		for (InstructionListener listener : instructionListeners) {
			listener.tableSwitchInsn(instr, opcode);
		}
	}

	private static void notifyLookupSwitchInsn(int instr, int opcode) {
		for (InstructionListener listener : instructionListeners) {
			listener.lookupSwitchInsn(instr, opcode);
		}
	}

	private static void notifyMultiANewArrayInsn(int instr, int opcode) {
		for (InstructionListener listener : instructionListeners) {
			listener.multiANewArrayInsn(instr, opcode);
		}
	}

	// ======================================================================
	//
	// UTILITIES
	//
	// ======================================================================

	private static boolean isConstant(Expression conjunct) {
		if (conjunct instanceof Operation) {
			Operation operation = (Operation) conjunct;
			int n = operation.getOperatandCount();
			for (int i = 0; i < n; i++) {
				if (!isConstant(operation.getOperand(i))) {
					return false;
				}
			}
			return true;
		} else {
			return (conjunct instanceof Constant);
		}
	}

	private static int getArgumentCount(String descriptor) {
		int count = 0;
		int i = 0;
		if (descriptor.charAt(i++) != '(') {
			return 0;
		}
		while (true) {
			char ch = descriptor.charAt(i++);
			if (ch == ')') {
				return count;
			} else if ((ch == 'B') || (ch == 'C') || (ch == 'D') || (ch == 'F') || (ch == 'I') || (ch == 'J')
					|| (ch == 'S') || (ch == 'Z')) {
				count++;
			} else if (ch == 'L') {
				i = descriptor.indexOf(';', i);
				if (i == -1) {
					return 0; // missing ';'
				}
				i++;
				count++;
			} else if (ch != '[') {
				return 0; // unknown character in signature 
			}
		}
	}

	private static String getReturnType(String descriptor) {
		int i = 0;
		if (descriptor.charAt(i++) != '(') {
			return "?"; // missing '('
		}
		i = descriptor.indexOf(')', i);
		if (i == -1) {
			return "?"; // missing ')'
		}
		return descriptor.substring(i + 1);
	}

	public static String getAsciiSignature(String descriptor) {
		return descriptor.replace('/', '_').replace("_", "_1").replace(";", "_2").replace("[", "_3").replace("(", "__")
				.replace(")", "__");
	}

}

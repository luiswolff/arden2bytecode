package arden.runtime;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Static helper methods for ExpressionCompiler (mostly operators with special
 * list handling).
 * 
 * @author Daniel Grunwald
 */
public final class ExpressionHelpers {
	/** implements the ",x" operator */
	public static ArdenList unaryComma(ArdenValue value) {
		if (value instanceof ArdenList)
			return (ArdenList) value;
		else
			return new ArdenList(new ArdenValue[] { value });
	}

	/** implements the "x,y" operator */
	public static ArdenList binaryComma(ArdenValue lhs, ArdenValue rhs) {
		ArdenValue[] left = unaryComma(lhs).values;
		ArdenValue[] right = unaryComma(rhs).values;
		ArdenValue[] result = new ArdenValue[left.length + right.length];
		System.arraycopy(left, 0, result, 0, left.length);
		System.arraycopy(right, 0, result, left.length, right.length);
		return new ArdenList(result);
	}

	/** implements the SORT DATA operator */
	public static ArdenValue sortByData(ArdenValue inputVal) {
		ArdenList input = unaryComma(inputVal);
		if (input.values.length == 0)
			return input;
		// check whether all elements are comparable (also checks whether list
		// is already sorted):
		ArdenValue lastElement = input.values[0];
		boolean alreadySorted = true;
		for (int i = 1; i < input.values.length; i++) {
			ArdenValue thisElement = input.values[i];
			int r = lastElement.compareTo(thisElement);
			if (r == Integer.MIN_VALUE) {
				// list contains non-ordered element types or invalid comparison
				return ArdenNull.INSTANCE;
			} else if (r >= 0) {
				alreadySorted = false;
			}
			lastElement = thisElement;
		}
		if (alreadySorted)
			return input;
		ArdenValue[] result = (ArdenValue[]) input.values.clone();
		Arrays.sort(result, new Comparator<ArdenValue>() {
			@Override
			public int compare(ArdenValue o1, ArdenValue o2) {
				return o1.compareTo(o2);
			};
		});
		return new ArdenList(result);
	}

	/** implements the SORT TIME operator */
	public static ArdenValue sortByTime(ArdenValue inputVal) {
		ArdenList input = unaryComma(inputVal);
		if (input.values.length == 0)
			return input;
		// check whether all elements have a primary time:
		for (ArdenValue val : input.values) {
			if (val.primaryTime == ArdenValue.NOPRIMARYTIME)
				return ArdenNull.INSTANCE;
		}
		ArdenValue[] result = (ArdenValue[]) input.values.clone();
		Arrays.sort(result, new Comparator<ArdenValue>() {
			@Override
			public int compare(ArdenValue o1, ArdenValue o2) {
				if (o1.primaryTime < o2.primaryTime)
					return -1;
				else if (o1.primaryTime > o2.primaryTime)
					return 1;
				else
					return 0;
			};
		});
		return new ArdenList(result);
	}

	/** implements the WHERE operator */
	public static ArdenValue where(ArdenValue sequence, ArdenValue condition) {
		if (condition instanceof ArdenList) {
			ArdenValue[] conditionValues = ((ArdenList) condition).values;
			int numTrue = 0;
			for (ArdenValue cond : conditionValues) {
				if (cond.isTrue())
					numTrue++;
			}
			ArdenValue[] result = new ArdenValue[numTrue];
			if (sequence instanceof ArdenList) {
				ArdenValue[] sequenceValues = ((ArdenList) sequence).values;
				if (conditionValues.length != sequenceValues.length)
					return ArdenNull.INSTANCE;
				int pos = 0;
				for (int i = 0; i < conditionValues.length; i++) {
					if (conditionValues[i].isTrue())
						result[pos++] = sequenceValues[i];
				}
				assert pos == numTrue;
			} else {
				// 1 WHERE (true, true, false) ===> (1, 1)
				for (int i = 0; i < numTrue; i++)
					result[i] = sequence;
			}
			return new ArdenList(result);
		} else {
			// condition not ArdenList:
			return condition.isTrue() ? sequence : ArdenList.EMPTY;
		}
	}

	/** implements the COUNT OF operator */
	public static ArdenValue count(ArdenValue sequence) {
		return new ArdenNumber(unaryComma(sequence).values.length);
	}

	static long getCommonTime(ArdenValue[] items) {
		if (items.length == 0)
			return ArdenValue.NOPRIMARYTIME;
		long time = items[0].primaryTime;
		for (int i = 1; i < items.length; i++) {
			if (items[i].primaryTime != time)
				return ArdenValue.NOPRIMARYTIME;
		}
		return time;
	}

	/** implements the EXIST operator */
	public static ArdenValue exist(ArdenValue sequence) {
		ArdenList input = unaryComma(sequence);
		long primaryTime = getCommonTime(input.values);
		for (ArdenValue val : input.values) {
			if (!(val instanceof ArdenNull))
				return ArdenBoolean.create(true, primaryTime);
		}
		return ArdenBoolean.create(false, primaryTime);
	}

	/** implements the SUM operator */
	public static ArdenValue sum(ArdenValue sequence) {
		ArdenList input = unaryComma(sequence);
		if (input.values.length == 0)
			return ArdenNumber.ZERO;
		ArdenValue val = input.values[0];
		for (int i = 1; i < input.values.length; i++) {
			val = BinaryOperator.ADD.runElement(val, input.values[i]);
		}
		return val;
	}

	/** implements the MEDIAN operator */
	public static ArdenValue median(ArdenValue sequence) {
		ArdenValue sorted = sortByData(sequence);
		if (!(sorted instanceof ArdenList))
			return sorted; // error during sorting
		ArdenValue[] values = ((ArdenList) sorted).values;
		if (values.length == 0) {
			return ArdenNull.INSTANCE;
		} else if ((values.length % 2) == 1) {
			return values[values.length / 2];
		} else {
			return average(binaryComma(values[values.length / 2 - 1], values[values.length / 2]));
		}
	}

	/** implements the AVERAGE operator */
	public static ArdenValue average(ArdenValue sequence) {
		ArdenValue[] values = unaryComma(sequence).values;
		if (values.length == 0)
			return ArdenNull.INSTANCE;
		if (values[0] instanceof ArdenNumber) {
			double sum = 0;
			for (ArdenValue element : values) {
				if (!(element instanceof ArdenNumber))
					return ArdenNull.INSTANCE;
				sum += ((ArdenNumber) element).value;
			}
			return ArdenNumber.create(sum / values.length, getCommonTime(values));
		} else if (values[0] instanceof ArdenTime) {
			BigInteger sum = BigInteger.ZERO;
			for (ArdenValue element : values) {
				if (!(element instanceof ArdenTime))
					return ArdenNull.INSTANCE;
				sum = sum.add(BigInteger.valueOf(((ArdenTime) element).value));
			}
			sum = sum.divide(BigInteger.valueOf(values.length));
			return new ArdenTime(sum.longValue(), getCommonTime(values));
		} else if (values[0] instanceof ArdenDuration) {
			double sum = ((ArdenDuration) values[0]).value;
			boolean isMonths = ((ArdenDuration) values[0]).isMonths;
			for (int i = 1; i < values.length; i++) {
				if (!(values[i] instanceof ArdenDuration))
					return ArdenNull.INSTANCE;
				ArdenDuration d = (ArdenDuration) values[i];
				if (isMonths && !d.isMonths) {
					isMonths = false;
					sum *= ArdenDuration.SECONDS_PER_MONTH;
				}
				if (isMonths)
					sum += d.value;
				else
					sum += d.toSeconds();
			}
			return ArdenDuration.create(sum / values.length, isMonths, getCommonTime(values));
		} else {
			return ArdenNull.INSTANCE;
		}
	}

	/** implements the VARIANCE operator */
	public static ArdenValue variance(ArdenValue sequence) {
		// unlike the other operators, VARIANCE doesn't automatically build
		// 1-element-lists
		if (!(sequence instanceof ArdenList))
			return ArdenNull.INSTANCE;
		ArdenValue[] values = ((ArdenList) sequence).values;
		if (values.length == 0)
			return ArdenNull.INSTANCE;
		double sum = 0;
		for (ArdenValue element : values) {
			if (!(element instanceof ArdenNumber))
				return ArdenNull.INSTANCE;
			sum += ((ArdenNumber) element).value;
		}
		double avg = sum / values.length;
		double diffsum = 0;
		for (ArdenValue element : values) {
			double diff = avg - ((ArdenNumber) element).value;
			diffsum += diff * diff;
		}
		double variance = diffsum / values.length;
		return ArdenNumber.create(variance, getCommonTime(values));
	}

	/** implements the IS IN operator */
	public static ArdenValue isIn(ArdenValue lhs, ArdenValue rhs) {
		ArdenValue[] list = unaryComma(rhs).values;
		if (lhs instanceof ArdenList) {
			ArdenValue[] left = ((ArdenList) lhs).values;
			ArdenValue[] result = new ArdenValue[left.length];
			for (int i = 0; i < left.length; i++)
				result[i] = isIn(left[i], list);
			return new ArdenList(result);
		} else {
			return isIn(lhs, list);
		}
	}

	private static ArdenBoolean isIn(ArdenValue lhs, ArdenValue[] list) {
		for (ArdenValue val : list) {
			if (lhs.equals(val)) {
				if (val.primaryTime == lhs.primaryTime)
					return ArdenBoolean.create(true, val.primaryTime);
				else
					return ArdenBoolean.TRUE;
			}
		}
		return ArdenBoolean.FALSE;
	}

	/** implements the SEQTO operator */
	public static ArdenValue seqto(ArdenValue lhs, ArdenValue rhs) {
		if (!(lhs instanceof ArdenNumber) || !(rhs instanceof ArdenNumber))
			return ArdenNull.INSTANCE;
		// primary times are lost (as specified)
		double lower = ((ArdenNumber) lhs).value;
		double upper = ((ArdenNumber) rhs).value;
		int lowerInt = (int) lower;
		int upperInt = (int) upper;
		if (lowerInt != lower || upperInt != upper)
			return ArdenNull.INSTANCE;
		if (lowerInt > upperInt)
			return ArdenList.EMPTY;
		ArdenValue[] result = new ArdenValue[upperInt - lowerInt + 1];
		for (int i = 0; i < result.length; i++)
			result[i] = new ArdenNumber(lowerInt + i);
		return new ArdenList(result);
	}

	/** implements the SEQTO operator */
	public static ArdenValue reverse(ArdenValue input) {
		ArdenValue[] inputs = unaryComma(input).values;
		ArdenValue[] result = new ArdenValue[inputs.length];
		for (int i = 0; i < result.length; i++)
			result[i] = inputs[inputs.length - i - 1];
		return new ArdenList(result);
	}

	/** implements the INCREASE operator */
	public static ArdenValue increase(ArdenValue input) {
		ArdenValue[] inputs = unaryComma(input).values;
		if (inputs.length == 0)
			return ArdenNull.INSTANCE;
		ArdenValue[] outputs = new ArdenValue[inputs.length - 1];
		for (int i = 0; i < outputs.length; i++) {
			outputs[i] = BinaryOperator.SUB.runElement(inputs[i + 1], inputs[i]).setTime(inputs[i + 1].primaryTime);
		}
		return new ArdenList(outputs);
	}

	/** implements the PERCENT INCREASE operator */
	public static ArdenValue percentIncrease(ArdenValue input) {
		ArdenValue[] inputs = unaryComma(input).values;
		if (inputs.length == 0)
			return ArdenNull.INSTANCE;
		ArdenValue[] outputs = new ArdenValue[inputs.length - 1];
		for (int i = 0; i < outputs.length; i++) {
			outputs[i] = BinaryOperator.MUL.runElement(
					BinaryOperator.DIV.runElement(BinaryOperator.SUB.runElement(inputs[i + 1], inputs[i]), inputs[i]),
					ArdenNumber.ONE_HUNDRED).setTime(inputs[i + 1].primaryTime);
		}
		return new ArdenList(outputs);
	}

	/** Implements the IS LIST operator. */
	public static ArdenBoolean isList(ArdenValue input) {
		if (input instanceof ArdenList) {
			return ArdenBoolean.create(true, getCommonTime(((ArdenList) input).values));
		} else {
			return ArdenBoolean.create(false, input.primaryTime);
		}
	}

	/** Implements the FIRST aggregation operator. */
	public static ArdenValue first(ArdenValue input) {
		ArdenValue[] arr = unaryComma(input).values;
		if (arr.length == 0)
			return ArdenNull.INSTANCE;
		else
			return arr[0];
	}

	/** Implements the LAST aggregation operator. */
	public static ArdenValue last(ArdenValue input) {
		ArdenValue[] arr = unaryComma(input).values;
		if (arr.length == 0)
			return ArdenNull.INSTANCE;
		else
			return arr[arr.length - 1];
	}

	/** Implements the FIRST transformation operator. */
	public static ArdenValue first(ArdenValue input, int numberOfElements) {
		ArdenList inputList = unaryComma(input);
		if (numberOfElements >= inputList.values.length)
			return inputList;
		ArdenValue[] result = new ArdenValue[numberOfElements];
		System.arraycopy(inputList.values, 0, result, 0, numberOfElements);
		return new ArdenList(result);
	}

	/** Implements the LAST transformation operator. */
	public static ArdenValue last(ArdenValue input, int numberOfElements) {
		ArdenList inputList = unaryComma(input);
		if (numberOfElements >= inputList.values.length)
			return inputList;
		ArdenValue[] result = new ArdenValue[numberOfElements];
		System.arraycopy(inputList.values, inputList.values.length - numberOfElements, result, 0, numberOfElements);
		return new ArdenList(result);
	}

	/** Implements the MINIMUM aggregation operator. */
	public static ArdenValue minimum(ArdenValue input) {
		ArdenValue[] arr = unaryComma(input).values;
		if (arr.length == 0)
			return ArdenNull.INSTANCE;
		ArdenValue min = arr[0];
		for (int i = 1; i < arr.length; i++) {
			int r = min.compareTo(arr[i]);
			if (r == Integer.MIN_VALUE)
				return ArdenNull.INSTANCE;
			if (r == 1 || (r == 0 && arr[i].primaryTime > min.primaryTime))
				min = arr[i];
		}
		return min;
	}

	/** Implements the MAXIMUM aggregation operator. */
	public static ArdenValue maximum(ArdenValue input) {
		ArdenValue[] arr = unaryComma(input).values;
		if (arr.length == 0)
			return ArdenNull.INSTANCE;
		ArdenValue max = arr[0];
		for (int i = 1; i < arr.length; i++) {
			int r = max.compareTo(arr[i]);
			if (r == Integer.MIN_VALUE)
				return ArdenNull.INSTANCE;
			if (r == -1 || (r == 0 && arr[i].primaryTime > max.primaryTime))
				max = arr[i];
		}
		return max;
	}

	/** implements the ANY operator */
	public static ArdenValue any(ArdenValue sequence) {
		ArdenList input = unaryComma(sequence);
		long primaryTime = getCommonTime(input.values);
		boolean allFalse = true;
		for (ArdenValue val : input.values) {
			if (val.isTrue())
				return ArdenBoolean.create(true, primaryTime);
			allFalse &= val.isFalse();
		}
		if (allFalse)
			return ArdenBoolean.create(false, primaryTime);
		else
			return ArdenNull.create(primaryTime);
	}

	/** implements the ALL operator */
	public static ArdenValue all(ArdenValue sequence) {
		ArdenList input = unaryComma(sequence);
		long primaryTime = getCommonTime(input.values);
		boolean allTrue = true;
		for (ArdenValue val : input.values) {
			if (val.isFalse())
				return ArdenBoolean.create(false, primaryTime);
			allTrue &= val.isTrue();
		}
		if (allTrue)
			return ArdenBoolean.create(true, primaryTime);
		else
			return ArdenNull.create(primaryTime);
	}

	/** Implements the string concatenation operator || */
	public static ArdenString concat(ArdenValue lhs, ArdenValue rhs) {
		return new ArdenString(toString(lhs) + toString(rhs));
	}

	/** Converts a single value to string. */
	public static String toString(ArdenValue val) {
		if (val instanceof ArdenString)
			return ((ArdenString) val).value;
		else
			return val.toString();
	}

	/** implements the STRING... operator */
	public static ArdenString joinString(ArdenValue input) {
		StringBuilder b = new StringBuilder();
		for (ArdenValue val : unaryComma(input).values) {
			b.append(toString(val));
		}
		return new ArdenString(b.toString());
	}

	/** implements the EXTRACT CHARACTERS operator */
	public static ArdenList extractCharacters(ArdenValue input) {
		ArrayList<String> strings = new ArrayList<String>();
		for (ArdenValue val : unaryComma(input).values) {
			String txt = toString(val);
			for (int i = 0; i < txt.length(); i++)
				strings.add(txt.substring(i, i + 1));
		}
		ArdenValue[] result = new ArdenValue[strings.size()];
		for (int i = 0; i < result.length; i++)
			result[i] = new ArdenString(strings.get(i));
		return new ArdenList(result);
	}

	/** implements the TRIM operator */
	public static ArdenValue trim(ArdenValue input) {
		if (input instanceof ArdenList) {
			ArdenValue[] list = ((ArdenList) input).values;
			if (list.length == 0)
				return ArdenNull.INSTANCE; // special case
			ArdenValue[] results = new ArdenValue[list.length];
			for (int i = 0; i < list.length; i++)
				results[i] = trim(list[i]);
			return new ArdenList(results);
		} else if (input instanceof ArdenString) {
			return new ArdenString(((ArdenString) input).value.trim(), input.primaryTime);
		} else {
			return ArdenNull.create(input.primaryTime);
		}
	}

	/** implements the TRIM LEFT operator */
	public static ArdenValue trimLeft(ArdenValue input) {
		if (input instanceof ArdenList) {
			ArdenValue[] list = ((ArdenList) input).values;
			if (list.length == 0)
				return ArdenNull.INSTANCE; // special case
			ArdenValue[] results = new ArdenValue[list.length];
			for (int i = 0; i < list.length; i++)
				results[i] = trimLeft(list[i]);
			return new ArdenList(results);
		} else if (input instanceof ArdenString) {
			String str = ((ArdenString) input).value;
			int index = 0;
			while (index < str.length() && Character.isWhitespace(str.charAt(index)))
				index++;
			return new ArdenString(str.substring(index), input.primaryTime);
		} else {
			return ArdenNull.create(input.primaryTime);
		}
	}

	/** implements the TRIM RIGHT operator */
	public static ArdenValue trimRight(ArdenValue input) {
		if (input instanceof ArdenList) {
			ArdenValue[] list = ((ArdenList) input).values;
			if (list.length == 0)
				return ArdenNull.INSTANCE; // special case
			ArdenValue[] results = new ArdenValue[list.length];
			for (int i = 0; i < list.length; i++)
				results[i] = trimRight(list[i]);
			return new ArdenList(results);
		} else if (input instanceof ArdenString) {
			String str = ((ArdenString) input).value;
			int index = str.length();
			while (index > 0 && Character.isWhitespace(str.charAt(index - 1)))
				index--;
			return new ArdenString(str.substring(0, index), input.primaryTime);
		} else {
			return ArdenNull.create(input.primaryTime);
		}
	}

	/** implements the LENGTH OF operator */
	public static ArdenValue length(ArdenValue input) {
		if (input instanceof ArdenList) {
			ArdenValue[] list = ((ArdenList) input).values;
			if (list.length == 0)
				return ArdenNull.INSTANCE; // special case
			ArdenValue[] results = new ArdenValue[list.length];
			for (int i = 0; i < list.length; i++)
				results[i] = length(list[i]);
			return new ArdenList(results);
		} else if (input instanceof ArdenString) {
			String str = ((ArdenString) input).value;
			return ArdenNumber.create(str.length(), ArdenValue.NOPRIMARYTIME);
		} else {
			return ArdenNull.INSTANCE;
		}
	}

	/** implements the UPPERCASE operator */
	public static ArdenValue toUpperCase(ArdenValue input) {
		if (input instanceof ArdenList) {
			ArdenValue[] list = ((ArdenList) input).values;
			if (list.length == 0)
				return ArdenNull.INSTANCE; // special case
			ArdenValue[] results = new ArdenValue[list.length];
			for (int i = 0; i < list.length; i++)
				results[i] = toUpperCase(list[i]);
			return new ArdenList(results);
		} else if (input instanceof ArdenString) {
			String str = ((ArdenString) input).value;
			return new ArdenString(str.toUpperCase(), input.primaryTime);
		} else {
			return ArdenNull.create(input.primaryTime);
		}
	}

	/** implements the LOWERCASE operator */
	public static ArdenValue toLowerCase(ArdenValue input) {
		if (input instanceof ArdenList) {
			ArdenValue[] list = ((ArdenList) input).values;
			if (list.length == 0)
				return ArdenNull.INSTANCE; // special case
			ArdenValue[] results = new ArdenValue[list.length];
			for (int i = 0; i < list.length; i++)
				results[i] = toLowerCase(list[i]);
			return new ArdenList(results);
		} else if (input instanceof ArdenString) {
			String str = ((ArdenString) input).value;
			return new ArdenString(str.toLowerCase(), input.primaryTime);
		} else {
			return ArdenNull.create(input.primaryTime);
		}
	}

	/** implements the MATCHES PATTERN operator */
	public static ArdenValue matchesPattern(ArdenValue lhs, ArdenValue rhs) {
		Pattern pattern = createPattern(rhs);
		if (lhs instanceof ArdenString) {
			String input = ((ArdenString) lhs).value;
			return pattern.matcher(input).matches() ? ArdenBoolean.TRUE : ArdenBoolean.FALSE;
		} else if (lhs instanceof ArdenList) {
			ArdenValue[] inputs = ((ArdenList) lhs).values;
			ArdenValue[] results = new ArdenValue[inputs.length];
			for (int i = 0; i < inputs.length; i++) {
				if (inputs[i] instanceof ArdenString) {
					String input = ((ArdenString) inputs[i]).value;
					results[i] = pattern.matcher(input).matches() ? ArdenBoolean.TRUE : ArdenBoolean.FALSE;
				} else {
					results[i] = ArdenNull.INSTANCE;
				}
			}
			return new ArdenList(results);
		} else {
			return ArdenNull.INSTANCE;
		}
	}

	/** helper for MATCHES PATTERN implementation */
	private static Pattern createPattern(ArdenValue rhs) {
		if (rhs instanceof ArdenString) {
			String pattern = ((ArdenString) rhs).value;
			StringBuilder regex = new StringBuilder();
			int processingEndOffset = 0;
			regex.append('^');
			for (int i = 0; i < pattern.length(); i++) {
				char c = pattern.charAt(i);
				if (c == '_' || c == '%') {
					if (processingEndOffset < i)
						regex.append(Pattern.quote(pattern.substring(processingEndOffset, i)));
					regex.append('.');
					if (c == '%')
						regex.append('*');
					processingEndOffset = i + 1;
				} else if (c == '\\') {
					if (processingEndOffset < i)
						regex.append(Pattern.quote(pattern.substring(processingEndOffset, i)));
					processingEndOffset = i + 1; // don't output the \ itself
					i++; // skip processing the character after the \, thus
					// copying it to the output escaped
				}
			}
			if (processingEndOffset < pattern.length())
				regex.append(Pattern.quote(pattern.substring(processingEndOffset, pattern.length())));
			regex.append('$');
			return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
		} else {
			return null;
		}
	}
}

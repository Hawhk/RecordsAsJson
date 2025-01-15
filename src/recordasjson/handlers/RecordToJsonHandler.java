package recordasjson.handlers;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorActionDelegate;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;

public class RecordToJsonHandler implements IEditorActionDelegate {
	private IEditorPart editor;
	private ICompilationUnit currentUnit;
	private LocalDateTime localDateTime;
	private ZoneId zoneId;

	@Override
	public void setActiveEditor(IAction action, IEditorPart targetEditor) {
		this.editor = targetEditor;
		if (action != null && editor != null) {
			action.setEnabled(shouldBeActive());
		}
	}

	@Override
	public void run(IAction action) {
		if (editor == null) {
			return;
		}

		localDateTime = LocalDateTime.now();
		zoneId = ZoneId.systemDefault();

		try {
			IEditorInput input = editor.getEditorInput();
			if (!(input instanceof IFileEditorInput)) {
				return;
			}

			IFileEditorInput fileInput = (IFileEditorInput) input;
			currentUnit = JavaCore.createCompilationUnitFrom(fileInput.getFile());

			if (currentUnit == null) {
				return;
			}

			IType[] types = currentUnit.getTypes();

			for (IType type : types) {
				String json = null;
				if (type.isRecord()) {
					json = generateJsonForRecord(type, 1);
				} else {
					json = generateJsonForClass(type, 1);
				}

				if (json != null) {
					System.out.println("Generated JSON: " + json);
					copyToClipboard(json);
					break;
				}
			}
		} catch (JavaModelException e) {
			MessageDialog.openError(editor.getSite().getShell(), "Error", "Error processing record: " + e.getMessage());
			e.printStackTrace();
		} finally {
			currentUnit = null;
		}
	}

	@Override
	public void selectionChanged(IAction action, ISelection selection) {
		// NO-OP
	}

	private String generateJsonForRecord(IType recordType, int depth) throws JavaModelException {

		String source = recordType.getCompilationUnit().getSource();
		String recordName = recordType.getElementName();

		int recordStart = source.indexOf("record " + recordName);
		int startPos = source.indexOf('(', recordStart);
		return makeJsonOfSource(startPos, depth, source);
	}

	private String generateJsonForClass(IType type, int depth) throws JavaModelException {

		IMethod[] methods = type.getMethods();
		IMethod constructor = null;
		for (IMethod method : methods) {
			constructor = getIfJsonCreator(method);
			if (constructor != null) {
				break;
			}
		}

		if (constructor != null) {
			String source = constructor.getSource();
			int startPos = source.indexOf('(');
			return makeJsonOfSource(startPos, depth, source);
		}

		return "{}";
	}

	private String makeJsonOfSource(int startPos, int depth, String source) {
		if (startPos != -1) {
			int endPos = findClosingParenthesis(source, startPos);
			if (endPos != -1) {
				String components = source.substring(startPos + 1, endPos).trim();
				String[] fields = splitTopLevelCommas(components);
				return makeJson(fields, depth);
			}
		}

		return "{}";
	}

	private IMethod getIfJsonCreator(IMethod method) throws JavaModelException {
		IMethod constructor = null;
		if (method.isConstructor()) {
			for (IAnnotation annotation : method.getAnnotations()) {
				if (annotation.getElementName().equals("JsonCreator")) {
					constructor = method;
					break;
				}
			}
		}
		return constructor;
	}

	private String[] splitTopLevelCommas(String input) {
		List<String> result = new ArrayList<>();
		int depth = 0;
		int startIndex = 0;

		String startParams = "(<{[";
		String endParams = ">)}]";

		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (startParams.indexOf(c) != -1) {
				depth++;
			} else if (endParams.indexOf(c) != -1) {
				depth--;
			} else if (c == ',' && depth == 0) {
				// Only split when we're not inside angle brackets
				result.add(input.substring(startIndex, i).trim());
				startIndex = i + 1;
			}
		}

		// Add the last part
		if (startIndex < input.length()) {
			result.add(input.substring(startIndex).trim());
		}

		return result.toArray(new String[0]);
	}

	private String makeJson(String[] fields, int depth) {
		StringBuilder json = new StringBuilder("{\n");
		boolean first = true;

		String tabs = "\t".repeat(depth);

		for (String field : fields) {
			field = field.trim();

			if (field.isEmpty() || field.split("\\s+").length < 2) {
				continue;
			}

			if (!first) {
				json.append(",\n");
			}
			first = false;
			json.append(tabs);

			field = removeAnnotations(field);
			String[] parts = field.split("\\s+");

			String fieldType = String.join(" ", Arrays.copyOfRange(parts, 0, parts.length - 1));
			String fieldName = parts[parts.length - 1];

			json.append(generateJsonForField(fieldType, fieldName, depth));
		}

		tabs = "\t".repeat(depth - 1);
		json.append("\n" + tabs + "}");
		return json.toString();
	}

	private String generateJsonForField(String fieldType, String fieldName, int depth) {
		return String.format("\"%s\": %s", fieldName, generateDefaultValueForType(fieldType, depth));
	}

	private String generateDefaultValueForType(String fieldType, int depth) {

		String value = null;

		if (fieldType.contains("<")) {
			value = handleGenerics(fieldType, depth);
		}

		System.out.println("Generating default value for type: " + fieldType);

		if (value == null) {
			value = switch (fieldType) {
			case "int", "long", "short", "byte", "Integer", "Long", "Short", "Byte" -> "0";
			case "double", "float", "BigDecimal", "Double", "Float" -> "0.0";
			case "boolean", "Boolean" -> "false";
			case "String", "char", "Character" -> "\"\"";
			case "LocalDate" -> localDateTime.format(DateTimeFormatter.ofPattern("\"yyyy-MM-dd\""));
			case "LocalDateTime" -> localDateTime.format(DateTimeFormatter.ofPattern("\"yyyy-MM-dd'T'HH:mm:ss.SSS\""));
			case "YearMonth" -> localDateTime.format(DateTimeFormatter.ofPattern("\"yyyy-MM\""));
			case "Year" -> localDateTime.format(DateTimeFormatter.ofPattern("\"yyyy\""));
			case "LocalTime" -> localDateTime.format(DateTimeFormatter.ofPattern("\"HH:mm:ss.SSS\""));
			case "OffsetDateTime", "ZonedDateTime" ->
				localDateTime.atZone(zoneId).format(DateTimeFormatter.ofPattern("\"yyyy-MM-dd'T'HH:mm:ss.SSSXXX\""));
			default -> getNestedValues(fieldType, depth);
			};
		}

		return value;
	}

	private String getNestedValues(String fieldType, int depth) {
		try {
			if (currentUnit != null) {
				IType foundType = findType(fieldType);

				if (foundType == null) {
					return "";
				} else if (!isProjectSource(foundType)) {
					return "{} // External type";
				} else if (foundType.isRecord()) {
					return generateJsonForRecord(foundType, depth + 1);
				} else if (foundType.isClass()) {
					return generateJsonForClass(foundType, depth + 1);
				} else if (foundType.isEnum()) {
					return "\"\"";
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return "{}";
	}

	private boolean isProjectSource(IType foundType) {
		boolean isProjectSource = false;
		if (!foundType.isBinary()) {
			ICompilationUnit unit = foundType.getCompilationUnit();
			if (unit != null) {
				// It's a source file in your project
				isProjectSource = true;
			}
		} else {
			isProjectSource = false;
		}
		return isProjectSource;
	}

	private IType findType(String fieldType) throws CoreException {
		IJavaSearchScope searchScope = SearchEngine
				.createJavaSearchScope(new IJavaElement[] { currentUnit.getJavaProject() });

		SearchPattern pattern = SearchPattern.createPattern(fieldType, IJavaSearchConstants.TYPE,
				IJavaSearchConstants.DECLARATIONS, SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE);

		var requestor = new SearchRequestor() {
			private IType foundType = null;

			@Override
			public void acceptSearchMatch(SearchMatch match) throws CoreException {
				if (match.getElement() instanceof IType type && isProjectSource(type)) {
					foundType = type;
				}
			}

			public IType getFoundType() {
				return foundType;
			}
		};

		SearchEngine searchEngine = new SearchEngine();
		searchEngine.search(pattern, new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
				searchScope, requestor, new NullProgressMonitor());

		return requestor.getFoundType();
	}

	private String handleGenerics(String fullType, int depth) {
		int startGeneric = fullType.indexOf('<');
		int endGeneric = fullType.lastIndexOf('>');

		if (startGeneric == -1 || endGeneric == -1) {
			return "\"\""; // Invalid generic syntax
		}

		String baseType = fullType.substring(0, startGeneric).trim();
		String genericParams = fullType.substring(startGeneric + 1, endGeneric).trim();

		List<String> params = splitGenericParams(genericParams);

		return switch (baseType) {
		case "List", "ArrayList", "Set", "HashSet", "Collection", "LinkedList" -> {
			if (params.isEmpty()) {
				yield "[]";
			}
			String param = params.get(0);
			yield String.format("[ %s ]", generateDefaultValueForType(param, depth));
		}
		case "Map", "HashMap" -> {
			if (params.size() < 2) {
				yield "{}";
			}
			String keyType = params.get(0);
			String valueType = params.get(1);
			yield String.format("{ %s: %s }", generateDefaultValueForType(keyType, depth),
					generateDefaultValueForType(valueType, depth));
		}
		case "Optional" -> {
			if (params.isEmpty()) {
				yield "null";
			}
			String param = params.get(0);

			yield generateDefaultValueForType(param, depth);
		}

		default -> "{}";

		};
	}

	private List<String> splitGenericParams(String params) {
		List<String> result = new ArrayList<>();
		AtomicInteger depth = new AtomicInteger(0);
		StringBuilder current = new StringBuilder();

		for (Character c : params.toCharArray()) {
			switch (c) {
			case '<' -> depth.getAndIncrement();
			case '>' -> depth.getAndDecrement();
			case Character ch when ch == ',' && depth.get() == 0 -> {
				result.add(current.toString().trim());
				current = new StringBuilder();
				continue;
			}
			default -> {
			}
			}
			current.append(c);
		}

		String lastParam = current.toString().trim();
		if (!lastParam.isEmpty()) {
			result.add(lastParam);
		}

		return result;
	}

	private boolean shouldBeActive() {
		if (editor == null) {
			return false;
		}
		try {
			IEditorInput input = editor.getEditorInput();
			if (input instanceof IFileEditorInput fileInput) {
				ICompilationUnit unit = JavaCore.createCompilationUnitFrom(fileInput.getFile());
				if (unit != null) {
					for (IType type : unit.getTypes()) {
						if (isProjectSource(type)) {
							return true;
						}
					}
				}
			}
		} catch (JavaModelException e) {
			e.printStackTrace();
		}

		return false;
	}

	private void copyToClipboard(String text) {
		Display display = Display.getCurrent();
		Clipboard clipboard = new Clipboard(display);
		TextTransfer textTransfer = TextTransfer.getInstance();
		clipboard.setContents(new Object[] { text }, new Transfer[] { textTransfer });
		clipboard.dispose();
	}

	private int findClosingParenthesis(String source, int startPos) {
		int depth = 0;
		for (int i = startPos; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1; // No matching parenthesis found
	}

	private String removeAnnotations(String fieldDeclaration) {
		int depth = 0;
		boolean inAnnotation = false;

		// Find where actual field declaration starts (after annotations)
		for (int i = 0; i < fieldDeclaration.length(); i++) {
			char c = fieldDeclaration.charAt(i);

			if (c == '@') {
				inAnnotation = true;
			} else if (inAnnotation && c == '(') {
				depth++;

			} else if (inAnnotation && c == ')') {
				depth--;
			} else if (inAnnotation && depth == 0 && Character.isWhitespace(c)) {
				inAnnotation = false;
			} else if (!inAnnotation && !Character.isWhitespace(c)) {
				// Found start of actual field declaration
				return fieldDeclaration.substring(i);
			}

			if (inAnnotation && depth == 0 && c == ')') {
				inAnnotation = false;
			}
		}

		return fieldDeclaration;
	}
}
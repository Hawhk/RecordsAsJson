package recordasjson.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
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

	@Override
	public void setActiveEditor(IAction action, IEditorPart targetEditor) {
		this.editor = targetEditor;
		if (action != null && editor != null) {
			action.setEnabled(isRecordPresent());
		}
	}

	@Override
	public void run(IAction action) {
		if (editor == null) {
			return;
		}

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

			String source = currentUnit.getSource();
			IType[] types = currentUnit.getTypes();

			for (IType type : types) {
				if (type.isRecord()) {
					copyRecordAsJson(source, type);
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

	private void copyRecordAsJson(String source, IType type) {
		// Find the record declaration
		int startPos = source.indexOf("record " + type.getElementName());
		if (startPos != -1) {
			int openParen = source.indexOf('(', startPos);
			int closeParen = findClosingParenthesis(source, openParen);
			if (openParen != -1 && closeParen != -1) {
				// Extract the components part
				String components = source.substring(openParen + 1, closeParen).trim();
				String[] fields = splitTopLevelCommas(components);

				String json = makeJson(fields);
				System.out.println("Generated JSON: " + json);
				copyToClipboard(json);
			}
		}
	}

	private String[] splitTopLevelCommas(String input) {
		List<String> result = new ArrayList<>();
		int depth = 0;
		int startIndex = 0;

		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (c == '<') {
				depth++;
			} else if (c == '>') {
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

	private String makeJson(String[] fields) {
		StringBuilder json = new StringBuilder("{\n");
		boolean first = true;

		for (String field : fields) {
			field = field.trim();

			if (field.isEmpty() || field.split("\\s+").length < 2) {
				continue;
			}

			if (!first) {
				json.append(",\n");
			}
			first = false;

			field = removeAnnotations(field);
			String[] parts = field.split("\\s+");

			String fieldType = String.join(" ", Arrays.copyOfRange(parts, 0, parts.length - 1));
			String fieldName = parts[parts.length - 1];

			json.append(generateJsonForField(fieldType, fieldName));
		}

		json.append("\n}");
		return json.toString();
	}

	private String generateJsonForField(String fieldType, String fieldName) {
		return String.format("  \"%s\": %s", fieldName, generateDefaultValueForType(fieldType));
	}

	private String generateDefaultValueForType(String fieldType) {

		String value = null;

		System.out.println("Generating default value for type: " + fieldType);
		if (fieldType.contains("<")) {
			value = handleGenerics(fieldType);
		}

		if (value == null) {
			value = switch (fieldType) {
			case "int", "long", "short", "byte", "Integer", "Long", "Short", "Byte" -> "0";
			case "double", "float", "BigDecimal", "Double", "Float" -> "0.0";
			case "boolean", "Boolean" -> "false";
			case "String", "char", "Character" -> "\"\"";
			case "LocalDate" -> "\"2025-01-12\"";
			case "LocalDateTime" -> "\"2025-01-12T12:00:00\"";
			default -> getNestedValues(fieldType);
			};
		}

		return value;
	}

	private String getNestedValues(String fieldType) {
		try {
			if (currentUnit != null) {
				IType foundType = findType(fieldType);
				if (foundType != null && foundType.isRecord()) {
					return generateJsonForNestedRecord(foundType);
				} else if (foundType != null && foundType.isEnum()) {
					return "\"\"";
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return "{}";
	}

	private IType findType(String fieldType) throws CoreException {
		IJavaSearchScope searchScope = SearchEngine
				.createJavaSearchScope(new IJavaElement[] { currentUnit.getJavaProject() });

		SearchPattern pattern = SearchPattern.createPattern(fieldType, IJavaSearchConstants.TYPE,
				IJavaSearchConstants.DECLARATIONS, SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE);

		var requestor = new SearchRequestor() {
			private IType foundRecord = null;

			@Override
			public void acceptSearchMatch(SearchMatch match) throws CoreException {
				if (match.getElement() instanceof IType type) {
					foundRecord = type;
				}
			}

			public IType getFoundType() {
				return foundRecord;
			}
		};

		SearchEngine searchEngine = new SearchEngine();
		searchEngine.search(pattern, new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
				searchScope, requestor, new NullProgressMonitor());

		return requestor.getFoundType();
	}

	private String generateJsonForNestedRecord(IType recordType) {
		try {
			String source = recordType.getCompilationUnit().getSource();
			String recordName = recordType.getElementName();

			// Find the record declaration
			int startPos = source.indexOf("record " + recordName);
			if (startPos != -1) {
				int openParen = source.indexOf('(', startPos);
				int closeParen = findClosingParenthesis(source, openParen);
				if (openParen != -1 && closeParen != -1) {
					String components = source.substring(openParen + 1, closeParen).trim();
					String[] fields = splitTopLevelCommas(components);
					return makeJson(fields);
				}
			}
		} catch (JavaModelException e) {
			e.printStackTrace();
		}
		return "{}";
	}

	private String handleGenerics(String fullType) {
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
			yield String.format("[ %s ]", generateDefaultValueForType(param));
		}
		case "Map", "HashMap" -> {
			if (params.size() < 2) {
				yield "{}";
			}
			String keyType = params.get(0);
			String valueType = params.get(1);
			yield String.format("{ %s: %s }", generateDefaultValueForType(keyType),
					generateDefaultValueForType(valueType));
		}
		case "Optional" -> {
			if (params.isEmpty()) {
				yield "null";
			}
			String param = params.get(0);

			yield generateDefaultValueForType(param);
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

	private boolean isRecordPresent() {
		if (editor == null) {
			return false;
		}
		try {
			IEditorInput input = editor.getEditorInput();
			if (input instanceof IFileEditorInput fileInput) {
				ICompilationUnit unit = JavaCore.createCompilationUnitFrom(fileInput.getFile());
				if (unit != null) {
					for (IType type : unit.getTypes()) {
						if (type.isRecord()) {
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
package recordasjson.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
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
			if (!(input instanceof IFileEditorInput))
				return;

			IFileEditorInput fileInput = (IFileEditorInput) input;
			ICompilationUnit unit = JavaCore.createCompilationUnitFrom(fileInput.getFile());

			if (unit == null) {
				return;
			}

			String source = unit.getSource();
			IType[] types = unit.getTypes();

			for (IType type : types) {
				if (type.isRecord()) {
					copyRecordAsJson(source, type);
					break;
				}
			}
		} catch (JavaModelException e) {
			MessageDialog.openError(editor.getSite().getShell(), "Error", "Error processing record: " + e.getMessage());
			e.printStackTrace();
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
			System.out.println("field: " + field);
			field = field.trim();
			System.out.println("field trimed: " + field);

			if (field.isEmpty() || field.split("\\s+").length < 2) {
				continue;
			}

			if (!first) {
				json.append(",\n");
			}
			first = false;

			field = removeAnnotations(field);
			String[] parts = field.split("\\s+");

			System.out.println("parts: " + Arrays.toString(parts));

			String fieldType = String.join(" ", Arrays.copyOfRange(parts, 0, parts.length - 1));
			String fieldName = parts[parts.length - 1];

			System.out.println("Field Type: " + fieldType + " Field Name: " + fieldName);

			json.append(generateJsonForField(fieldType, fieldName));
		}

		json.append("\n}");
		return json.toString();
	}

	private String generateJsonForField(String fieldType, String fieldName) {
		return String.format("  \"%s\": %s", fieldName, generateDefaultValueForType(fieldType));
	}

	private String generateDefaultValueForType(String fieldType) {

		System.out.println("Generating default value for type: " + fieldType);
		if (fieldType.contains("<")) {
			return handleGenerics(fieldType);
		}

		return switch (fieldType) {
		case "int", "long", "short", "byte", "Integer", "Long", "Short", "Byte" -> "0";
		case "double", "float", "BigDecimal", "Double", "Float" -> "0.0";
		case "boolean", "Boolean" -> "false";
		case "String", "char", "Character" -> "\"\"";
		case "LocalDate" -> "\"2025-01-12\"";
		case "LocalDateTime" -> "\"2025-01-12T12:00:00\"";
		default -> "\"\"";
		};
	}

	private String handleGenerics(String fullType) {
		int startGeneric = fullType.indexOf('<');
		int endGeneric = fullType.lastIndexOf('>');

		if (startGeneric == -1 || endGeneric == -1) {
			System.out.println("Invalid generic syntax for type: " + fullType);
			return "\"\""; // Invalid generic syntax
		}

		String baseType = fullType.substring(0, startGeneric).trim();
		String genericParams = fullType.substring(startGeneric + 1, endGeneric).trim();

		List<String> params = splitGenericParams(genericParams);

		System.out.println("Base type: " + baseType + " Params: " + params);

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
		int depth = 0;
		StringBuilder current = new StringBuilder();

		for (char c : params.toCharArray()) {
			switch (c) {
			case '<' -> depth++;
			case '>' -> depth--;
			case ',' -> {
				if (depth == 0) {
					result.add(current.toString().trim());
					current = new StringBuilder();
					continue;
				}
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
		int fieldStart = 0;

		// Find where actual field declaration starts (after annotations)
		for (int i = 0; i < fieldDeclaration.length(); i++) {
			char c = fieldDeclaration.charAt(i);

			if (c == '@') {
				inAnnotation = true;
				if (fieldStart == 0) {
					fieldStart = i;
				}
			} else if (inAnnotation) {
				if (c == '(') {
					depth++;
				} else if (c == ')') {
					depth--;
					if (depth == 0) {
						inAnnotation = false;
					}
				} else if (depth == 0 && Character.isWhitespace(c)) {
					inAnnotation = false;
				}
			} else if (!inAnnotation && !Character.isWhitespace(c)) {
				// Found start of actual field declaration
				return fieldDeclaration.substring(i);
			}
		}

		return fieldDeclaration;
	}
}
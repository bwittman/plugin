/*
 * This code borrows heavily from the Ceylon source-handling portion of their plug-in:
 * https://github.com/ceylon/ceylon-ide-eclipse/blob/09ec642878392b614acf879a26afb1db3296cb2d/plugins/com.redhat.ceylon.eclipse.ui/src/com/redhat/ceylon/eclipse/code/editor/CeylonSourceViewer.java
 */

package shadow.plugin.editor;

import java.util.TreeSet;
import java.util.regex.Pattern;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentRewriteSession;
import org.eclipse.jface.text.DocumentRewriteSessionType;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.source.IOverviewRuler;
import org.eclipse.jface.text.source.IVerticalRuler;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.FileEditorInput;

import shadow.parse.ShadowParser.CompilationUnitContext;
import shadow.plugin.compiler.ShadowCompilerInterface;
import shadow.typecheck.Package;
import shadow.typecheck.type.ArrayType;
import shadow.typecheck.type.Type;

public class ShadowSourceViewer extends ProjectionViewer {
	
	private final Pattern ONE_LINER = Pattern.compile("\\s*(if|for|while|do|foreach|switch|case)\\s*\\(.*\\)\\s*");
	private ShadowEditor editor;

	public ShadowSourceViewer(Composite parent, IVerticalRuler ruler, IOverviewRuler overviewRuler, boolean showsAnnotationOverview, int styles, ShadowEditor editor) {
		super(parent, ruler, overviewRuler, showsAnnotationOverview, styles);
		this.editor = editor;
	}

	public void correctIndentation() {

		IDocument doc = getDocument();
		DocumentRewriteSession rewriteSession = null;
		if (doc instanceof IDocumentExtension4) {
			rewriteSession = 
					((IDocumentExtension4) doc)
					.startRewriteSession(DocumentRewriteSessionType.SEQUENTIAL);
		}
			

		Point selectedRange = getSelectedRange();
		boolean emptySelection = 
				selectedRange==null || selectedRange.y==0;
		
		int offset = selectedRange.x;
		int len = selectedRange.y;		

		try {
			correctSourceIndentation(offset, len, doc);
		} 
		catch (BadLocationException e) {
			e.printStackTrace();
		}
		finally {
			if (doc instanceof IDocumentExtension4) {
				((IDocumentExtension4) doc)
				.stopRewriteSession(rewriteSession);
			}
			restoreSelection();
			if (emptySelection) {
				selectedRange = getSelectedRange();
				setSelectedRange(selectedRange.x, 0);
			}
		}
	}
	
	private String getLineAfterTabs(String line) {
		for( int i = 0; i < line.length(); ) {			
			int c = line.codePointAt(i);
			if (c != ' ' && c != '\t')
				return line.substring(i);			

			i += Character.charCount(c);			
		}
		return "";
	}
	

	private boolean lookingAtLineEnd(IDocument doc, int pos) {
		String[] legalLineTerms = doc.getLegalLineDelimiters();
		try {
			for(String lineTerm: legalLineTerms) {
				int len = lineTerm.length();
				if (pos>len && 
						doc.get(pos-len,len).equals(lineTerm)) {
					return true;
				}
			}
		} 
		catch (BadLocationException e) {
			e.printStackTrace();
		}
		return false;
	}

	public void correctSourceIndentation(int selStart, int selLen, 
			IDocument doc)
					throws BadLocationException {
		int selEnd = selStart + selLen;
		int startLine = doc.getLineOfOffset(selStart);
		int endLine = doc.getLineOfOffset(selEnd);
	
		// If the selection extends just to the beginning of the next line, don't indent that one too
		if (selLen > 0 && 
				lookingAtLineEnd(doc, selEnd)) {
			endLine--;
		}
		
		int tabLevel = 0;
		int previousLine = startLine - 1;
		boolean lastIsOneLiner = false;
		
		
		//find initial tab level
		boolean keepLooking = true;
		while( previousLine >= 0 && keepLooking ) {
			IRegion region = doc.getLineInformation(previousLine);
			String text = doc.get(region.getOffset(), region.getLength());
			if( !text.trim().isEmpty() ) {
				text = text.replace("    ", "\t");
				lastIsOneLiner = ONE_LINER.matcher(text).matches();
				for( int i = 0; i < text.length() && keepLooking; ) {
				   final int codepoint = text.codePointAt(i);
				   if( codepoint == '\t' )
						tabLevel++;
					else if( !Character.isWhitespace(codepoint) )
						keepLooking = false;
				   i += Character.charCount(codepoint);
				}
				
				int braceChange = getBraceChange(text);				
				tabLevel += lastIsOneLiner ? braceChange + 1 : braceChange;				
				
				keepLooking = false;				
			}	
			
			previousLine--;
		}
		
		boolean inComment = false;

		for( int line = startLine; line <= endLine; line++ ) {
			int offset = doc.getLineOffset(line);
			int length = doc.getLineLength(line);
			String lineText = doc.get(offset, length);
			
			if( !lineText.trim().isEmpty() ) {		
				boolean beginsMultilineComment = !inComment && beginsMultilineComment(lineText);
				boolean endsMultilineComment = inComment && endsMultilineComment(lineText);
				
				StringBuffer buffer = new StringBuffer();
				String afterTabs = getLineAfterTabs(lineText);
				
				int braceChange = 0;
				
				if( !beginsMultilineComment && !inComment ) {
					braceChange = getBraceChange(lineText);
					if( afterTabs.startsWith("}")) {
						tabLevel--;
						braceChange++;
					}
					else if( afterTabs.startsWith("{") && lastIsOneLiner  )
						tabLevel--;
				}
				
				for( int i = 0; i < tabLevel; ++i )
					buffer.append('\t');
				if( inComment )
					buffer.append(' ');
				buffer.append(afterTabs);
				String newLine = buffer.toString();
				if( !newLine.equals(lineText) )
					doc.replace(offset, length, newLine );
				
				if( ONE_LINER.matcher(lineText).matches() ) {
					tabLevel++;
					lastIsOneLiner = true;
				}
				else {
					tabLevel += lastIsOneLiner ? braceChange - 1 : braceChange;
					lastIsOneLiner = false;
				}
				
				if( inComment && endsMultilineComment )
					inComment = false;
				else if( beginsMultilineComment )
					inComment = true;
			}
		}		
	}
	
	private boolean beginsMultilineComment(String lineText) {
		return lineText.matches(".*/\\*[^/]+[^(\\*/)]*");
	}
	
	private boolean endsMultilineComment(String lineText) {
		return lineText.matches(".*\\*/[^(/\\*)]*");
	}
	

	// x coordinate is left braces and y coordinate is right braces
	private int getBraceChange(String text) {
		int braceChange = 0;
		boolean inQuotes = false;
		boolean inComment = false;
		
		for( int i = 0; i < text.length(); ) {
		   final int codepoint = text.codePointAt(i);
		   if( codepoint == '"' && !inComment )
			   inQuotes = !inQuotes;		   
		   else if( !inQuotes && !inComment ) {
			   if( codepoint == '{' )
					braceChange++;
			   else if( codepoint == '}' )
					braceChange--;
			   else if( codepoint == '/' && i + 1 < text.length() && text.codePointAt(i + 1) == '*' ) {
				   i += 1;
				   inComment = true;
			   }
			   else if( codepoint == '/' && i < text.length() && text.codePointAt(i) == '/' )
				   return braceChange;
		   }
		   else if( inComment && codepoint == '*' && i + 1 < text.length() && text.codePointAt(i + 1) == '/'  ) {
			   i += 1;
			   inComment = false;
		   }			   
		   
		   i += Character.charCount(codepoint);
		}
		return braceChange;
	}
	
	public void generateElementComment() {
		IDocument doc = this.getDocument();
		DocumentRewriteSession rewriteSession = null;
		Point p = this.getSelectedRange();

		if (doc instanceof IDocumentExtension4) {
			rewriteSession = 
					((IDocumentExtension4) doc)
					.startRewriteSession(DocumentRewriteSessionType.SEQUENTIAL);
		}

		final int charOffset = p.x;				
		ShadowCompilerInterface.generateElementComment((FileEditorInput)editor.getEditorInput(), doc, charOffset);			
	
	
		if (doc instanceof IDocumentExtension4) {
			((IDocumentExtension4) doc)
			.stopRewriteSession(rewriteSession);
		}
		restoreSelection();		
	}


	public void addBlockComment() {
		IDocument doc = this.getDocument();
		DocumentRewriteSession rewriteSession = null;
		Point p = this.getSelectedRange();

		if (doc instanceof IDocumentExtension4) {
			rewriteSession = 
					((IDocumentExtension4) doc)
					.startRewriteSession(DocumentRewriteSessionType.SEQUENTIAL);
		}

		try {
			final int selStart = p.x;
			final int selLen = p.y;
			final int selEnd = selStart+selLen;
			if( selLen > 0 ) {
				doc.replace(selStart, 0, "/*");
				doc.replace(selEnd+2, 0, "*/");
			}
		} 
		catch (BadLocationException e) {
			e.printStackTrace();
		} 
		finally {
			if (doc instanceof IDocumentExtension4) {
				((IDocumentExtension4) doc)
				.stopRewriteSession(rewriteSession);
			}
			restoreSelection();
		}
	}

	public void removeBlockComment() {
		IDocument doc = this.getDocument();
		DocumentRewriteSession rewriteSession = null;
		Point p = this.getSelectedRange();

		if (doc instanceof IDocumentExtension4) {
			rewriteSession = 
					((IDocumentExtension4) doc)
					.startRewriteSession(DocumentRewriteSessionType.SEQUENTIAL);
		}

		try {
			final int selStart = p.x;
			final int selLen = p.y;
			final int selEnd = selStart+selLen;
			String text = doc.get();
			int open = text.indexOf("/*", selStart);
			if (open>selEnd) open = -1;
			if (open<0) {
				open = text.lastIndexOf("/*", selStart);
			}
			int close = -1;
			if (open>=0) {
				close = text.indexOf("*/", open);
			}
			if (close+2<selStart) close = -1;
			if (open>=0&&close>=0) {
				doc.replace(open, 2, "");
				doc.replace(close-2, 2, "");
			}
		}
		catch (BadLocationException e) {
			e.printStackTrace();
		}
		finally {
			if (doc instanceof IDocumentExtension4) {
				((IDocumentExtension4) doc)
				.stopRewriteSession(rewriteSession);
			}
			restoreSelection();
		}
	}
	
	private boolean linesHaveCommentPrefix(IDocument doc, 
            String lineCommentPrefix, int startLine, int endLine) {
        try {
            int docLen = doc.getLength();

            for (int line=startLine; line<=endLine; line++) {
                
                int lineStart = doc.getLineOffset(line);
                int lineEnd = lineStart + doc.getLineLength(line) - 1;
                int offset = lineStart;

                while( Character.isWhitespace(doc.getChar(offset)) && 
                        offset < lineEnd ) {
                    offset++;
                }
                
                if (docLen-offset > lineCommentPrefix.length() && 
                    doc.get(offset, lineCommentPrefix.length())
                            .equals(lineCommentPrefix)) {
                    // this line starts with the single-line comment prefix
                }
                else {
                    return false;
                }
            }
        }
        catch (BadLocationException e) {
            return false;
        }
        return true;
    }
	
	private int calculateLeadingSpace(IDocument doc, 
            int startLine, int endLine) {
        try {
            int result = Integer.MAX_VALUE;
            for (int line=startLine; line<=endLine; line++) {
                
                int lineStart = doc.getLineOffset(line);
                int lineEnd = lineStart + doc.getLineLength(line) - 1;
                int offset = lineStart;
                
                while( Character.isWhitespace(doc.getChar(offset)) && offset < lineEnd )
                    offset++;                
                
                int leadingSpaces = offset - lineStart;
                result = Math.min(result, leadingSpaces);
            }
            return result;
        }
        catch (BadLocationException e) {
            return 0;
        }
    }

	public void toggleComment() {
		IDocument doc = this.getDocument();
		DocumentRewriteSession rewriteSession = null;
		Point p = this.getSelectedRange();
		final String lineCommentPrefix = "//";

		if (doc instanceof IDocumentExtension4) {
			rewriteSession = 
					((IDocumentExtension4) doc)
					.startRewriteSession(DocumentRewriteSessionType.SEQUENTIAL);
		}

		try {
			final int selStart = p.x;
			final int selLen = p.y;
			final int selEnd = selStart+selLen;
			final int startLine = doc.getLineOfOffset(selStart);
			int endLine = doc.getLineOfOffset(selEnd);

			if (selLen>0 && lookingAtLineEnd(doc, selEnd))
				endLine--;

			boolean linesAllHaveCommentPrefix = 
					linesHaveCommentPrefix(doc, 
							lineCommentPrefix, 
							startLine, endLine);
			boolean useCommonLeadingSpace = true; // take from a preference?
					int leadingSpaceToUse = 
					useCommonLeadingSpace ? 
							calculateLeadingSpace(doc, 
									startLine, endLine) : 0;

							for (int line = startLine; line<=endLine; line++) {

								int lineStart = doc.getLineOffset(line);
								int lineEnd = lineStart+doc.getLineLength(line)-1;

								if (linesAllHaveCommentPrefix) {
									// remove the comment prefix from each line, wherever it occurs in the line
									int offset = lineStart;
									while( Character.isWhitespace(doc.getChar(offset)) && offset<lineEnd )
										offset++;
									
									// The first non-whitespace characters *must* be the single-line comment prefix
									doc.replace(offset, 
											lineCommentPrefix.length(), 
											"");
								}
								else {
									// add the comment prefix to each line, after however many spaces leadingSpaceToAdd indicates
									int offset = lineStart+leadingSpaceToUse;
									doc.replace(offset, 0, lineCommentPrefix);
								}
							}
		}
		catch (BadLocationException e) {
			e.printStackTrace();
		}
		finally {
			if (doc instanceof IDocumentExtension4) {
				((IDocumentExtension4) doc)
				.stopRewriteSession(rewriteSession);
			}
			restoreSelection();
		}
	}
	
	
	public void removeUnusedImports() {		
		IDocument doc = this.getDocument();
		DocumentRewriteSession rewriteSession = null;
		
		if (doc instanceof IDocumentExtension4) {
			rewriteSession = 
					((IDocumentExtension4) doc)
					.startRewriteSession(DocumentRewriteSessionType.SEQUENTIAL);
		}

		try {
			FileEditorInput input = (FileEditorInput)editor.getEditorInput();
			CompilationUnitContext compilationUnit = ShadowCompilerInterface.getCompilationUnit(input, doc);
			Type type = ShadowCompilerInterface.typeCheck(input, doc);
			
			//TODO: Version information is lost here (although the compiler currently doesn't use it)
			
			if( compilationUnit != null && type != null && compilationUnit.importDeclaration().size() > 0 ) {				
				TreeSet<String> imports = new TreeSet<String>();
							
				//imported items come from import statements and fully qualified classes
				for( Object importItem : type.getImportedItems() ) {
					if( importItem instanceof Type ) {
						Type importType = (Type)importItem;
						if( !importType.hasOuter() && type.getUsedTypes().contains(importType) && !importType.getPackage().toString().equals("shadow:standard"))
							imports.add(importType.toString(Type.PACKAGES));
							
					}
					else if( importItem instanceof Package ) {
						Package importPackage = (Package)importItem;
						if( !importPackage.toString().equals("shadow:standard"))
							for( Type referencedType : type.getUsedTypes() )
								if( !referencedType.hasOuter() && !(referencedType instanceof ArrayType) &&  referencedType.getPackage().equals( importPackage ) && !referencedType.isPrimitive() )
									imports.add(referencedType.toString(Type.PACKAGES));					
					}
				}
				
				StringBuilder builder = new StringBuilder();
				String newline = System.lineSeparator();
				
				for( String importType : imports )			
					builder.append("import " + importType + ";").append(newline);
				
				if( imports.size() > 0 )
					builder.append(newline);
								
				int start = compilationUnit.importDeclaration().get(0).start.getStartIndex();
				int stop = compilationUnit.modifiers().start.getStartIndex();
				
				doc.replace(start, stop - start, builder.toString() );
			}			
		}
		catch (BadLocationException e) {			
		}
		finally {
			if (doc instanceof IDocumentExtension4) {
				((IDocumentExtension4) doc)
				.stopRewriteSession(rewriteSession);
			}
			restoreSelection();
		}
	}
}

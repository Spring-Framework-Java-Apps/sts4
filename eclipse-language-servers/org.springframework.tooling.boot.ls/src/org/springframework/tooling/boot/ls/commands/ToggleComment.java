/*******************************************************************************
 * Copyright (c) 2018 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.tooling.boot.ls.commands;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.undo.DocumentUndoManagerRegistry;
import org.eclipse.text.undo.IDocumentUndoManager;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.handlers.HandlerUtil;
import org.springsource.ide.eclipse.commons.livexp.util.Log;

public class ToggleComment extends AbstractHandler {
	
	Pattern commmentPattern = Pattern.compile("^\\s*#");
	String commentPrefix = "#";
	
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		Object ei = HandlerUtil.getVariable(event, "activeEditorId");
		System.out.println(ei);
		try {
			ISelection sel = HandlerUtil.getCurrentSelection(event);
			if (sel instanceof TextSelection) {
				TextSelection textSel = (TextSelection) sel;
				int startLine = textSel.getStartLine();
				int endLine = textSel.getEndLine();
	
				IEditorInput editorInput = HandlerUtil.getActiveEditorInput(event);
				System.out.println(editorInput);
				IEditorPart editor = HandlerUtil.getActiveEditor(event);
				if (editor instanceof TextEditor) {
					TextEditor textEditor = (TextEditor) editor;
					IDocument doc = textEditor.getDocumentProvider().getDocument(editorInput);
					if (doc!=null) {
						System.out.println(doc.get());
						
						boolean hasComments = hasComments(doc, startLine, endLine);
						IDocumentUndoManager undo = DocumentUndoManagerRegistry.getDocumentUndoManager(doc);
						undo.beginCompoundChange();
						try {
							if (hasComments) {
								stripComments(doc, startLine, endLine);
							} else {
								addComments(doc, startLine, endLine);
							}
						} finally {
							undo.endCompoundChange();
						}
					}
				}
			}
		} catch (Exception e) {
			Log.log(e);
		}
		return null;
	}

	private void addComments(IDocument doc, int startLine, int endLine) throws MalformedTreeException, BadLocationException {
		MultiTextEdit edits = new MultiTextEdit();
		for (int line = startLine; line<=endLine; line++) {
			int lineStart = doc.getLineOffset(line);
			edits.addChild(new InsertEdit(lineStart, "#"));
		}
		System.out.println("--- appling edits ---");
		edits.apply(doc);
		System.out.println(doc.get());
	}

	private void stripComments(IDocument doc, int startLine, int endLine) throws MalformedTreeException, BadLocationException {
		MultiTextEdit edits = new MultiTextEdit();
		for (int line = startLine; line<=endLine; line++) {
			int lineStart = doc.getLineOffset(line);
			String lineText = getLineText(doc, line);
			Matcher matcher = commmentPattern.matcher(lineText);
			if (matcher.find()) {
				int end = lineStart + matcher.end();
				int start = end - commentPrefix.length();
				edits.addChild(new DeleteEdit(start, commentPrefix.length()));
			}
		}
		System.out.println("--- appling edits ---");
		edits.apply(doc);
		System.out.println(doc.get());
	}

	private boolean hasComments(IDocument doc, int startLine, int endLine) throws BadLocationException {
		for (int line = startLine; line <= endLine; line++) {
			String lineText = getLineText(doc, line);
			if (!commmentPattern.matcher(lineText).find()) {
				return false;
			}
		}
		return true;
	}

	private String getLineText(IDocument doc, int line) throws BadLocationException {
		return doc.get(doc.getLineOffset(line), doc.getLineLength(line));
	}

}
package org.jetbrains.plugins.coursecreator.format;

import com.google.gson.annotations.Expose;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.coursecreator.CCProjectService;

import java.io.File;
import java.util.List;

public class TaskWindow implements Comparable<TaskWindow> {

  @Expose public int line;
  @Expose public int start;
  @Expose public String hint;
  @Expose public String possible_answer;
  @Expose public int length;
  public String myTaskText;
  public int myReplacementLength;
  public int myIndex;

  public TaskWindow() {}

  public TaskWindow(int line, int start, int length, String selectedText) {
    this.line = line;
    this.start = start;
    myReplacementLength = length;
    this.possible_answer = selectedText;
  }

  public void setTaskText(@NotNull final String taskText) {
    myTaskText = taskText;
    length = myTaskText.length();
  }

  public String getTaskText() {
    return myTaskText;
  }

  public int getReplacementLength() {
    return myReplacementLength;
  }

  public void setHint(String hint) {
    this.hint = hint;
  }

  public String getHintName() {
    return hint;
  }

  public void removeResources(@NotNull final Project project) {
    if (hint != null) {
      VirtualFile hints = project.getBaseDir().findChild("hints");
      if (hints == null) {
        return;
      }
      File hintFile = new File(hints.getPath(), hint);
      CCProjectService.deleteProjectFile(hintFile, project);
    }
  }

  public void drawHighlighter(@NotNull final Editor editor, boolean useLength) {
    int startOffset = editor.getDocument().getLineStartOffset(line) + start;
    int highlighterLength = useLength ? length : myReplacementLength;
    int endOffset = startOffset + highlighterLength;
    TextAttributes defaultTestAttributes =
      EditorColorsManager.getInstance().getGlobalScheme().getAttributes(EditorColors.LIVE_TEMPLATE_ATTRIBUTES);
    defaultTestAttributes.setEffectColor(JBColor.BLUE);
    RangeHighlighter highlighter =
      editor.getMarkupModel().addRangeHighlighter(startOffset, endOffset, HighlighterLayer.LAST + 1, defaultTestAttributes,
                                                  HighlighterTargetArea.EXACT_RANGE);
    highlighter.setGreedyToLeft(true);
    highlighter.setGreedyToRight(true);
  }

  public int getIndex() {
    return myIndex;
  }

  public void setIndex(int index) {

    myIndex = index;
  }

  public void setReplacementLength(int replacementLength) {
    myReplacementLength = replacementLength;
  }

  public int getLine() {
    return line;
  }

  public int getRealStartOffset(Document document) {
    return document.getLineStartOffset(line) + start;
  }

  public void setLine(int line) {
    this.line = line;
  }

  public int getStart() {
    return start;
  }

  public void setStart(int start) {
    this.start = start;
  }

  @Override
  public int compareTo(@NotNull TaskWindow taskWindow) {
    int lineDiff = line - taskWindow.line;
    if (lineDiff == 0) {
      return start - taskWindow.start;
    }
    return lineDiff;
  }

  public int getLength() {
    return length;
  }

  public void createGuardedBlocks(@NotNull final Editor editor) {
    Document document = editor.getDocument();
    if (document instanceof DocumentImpl) {
      DocumentImpl documentImpl = (DocumentImpl)document;
      List<RangeMarker> blocks = documentImpl.getGuardedBlocks();
        int start = getRealStartOffset(document);
        int end = start + getReplacementLength();
        if (start != 0) {
          createGuardedBlock(editor, blocks, start - 1, start);
        }
        if (end != document.getTextLength()) {
          createGuardedBlock(editor, blocks, end, end + 1);
        }
      }
  }

  public static void createGuardedBlock(Editor editor, List<RangeMarker> blocks, int start, int end) {
    RangeHighlighter rh = editor.getMarkupModel()
      .addRangeHighlighter(start, end, HighlighterLayer.LAST + 1, null, HighlighterTargetArea.EXACT_RANGE);
    blocks.add(rh);
  }

  public void setLength(int length) {
    this.length = length;
  }
}
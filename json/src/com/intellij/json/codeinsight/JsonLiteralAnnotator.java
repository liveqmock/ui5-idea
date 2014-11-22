package com.intellij.json.codeinsight;

import com.intellij.json.JsonBundle;
import com.intellij.json.highlighting.JsonSyntaxHighlighterFactory;
import com.intellij.json.psi.JsonNumberLiteral;
import com.intellij.json.psi.JsonPsiUtil;
import com.intellij.json.psi.JsonStringLiteral;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.regex.Pattern;

/**
 * @author Mikhail Golubev
 */
public class JsonLiteralAnnotator implements Annotator {
  private static final Pattern VALID_ESCAPE = Pattern.compile("\\\\([\"\\\\/bfnrt]|u[0-9a-fA-F]{4})");
  private static final Pattern VALID_NUMBER_LITERAL = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

  private static final boolean DEBUG = ApplicationManager.getApplication().isUnitTestMode();

  @Override
  public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
    if (element instanceof JsonStringLiteral) {
      final JsonStringLiteral stringLiteral = (JsonStringLiteral)element;
      final int elementOffset = element.getTextOffset();
      if (JsonPsiUtil.isPropertyKey(element)) {
        holder.createInfoAnnotation(element, DEBUG ? "property key" : null).setTextAttributes(JsonSyntaxHighlighterFactory.JSON_PROPERTY_KEY);
      }
      final String text = element.getText();
      final int length = text.length();

      // Check that string literal is closed properly
      if (length <= 1 || text.charAt(0) != text.charAt(length - 1) || quoteEscaped(text, length - 1)) {
        holder.createErrorAnnotation(element, JsonBundle.message("msg.missing.closing.quote"));
      }

      // Check escapes
      final List<Pair<TextRange, String>> fragments = stringLiteral.getTextFragments();
      for (Pair<TextRange, String> fragment : fragments) {
        final String fragmentText = fragment.getSecond();
        if (fragmentText.startsWith("\\") && fragmentText.length() > 1 && !VALID_ESCAPE.matcher(fragmentText).matches()) {
          final TextRange fragmentRange = fragment.getFirst();
          if (fragmentText.startsWith("\\u")) {
            holder.createErrorAnnotation(fragmentRange.shiftRight(elementOffset), JsonBundle.message("msg.illegal.unicode.escape.sequence"));
          }
          else {
            holder.createErrorAnnotation(fragmentRange.shiftRight(elementOffset), JsonBundle.message("msg.illegal.escape.sequence"));
          }
        }
      }
    }
    else if (element instanceof JsonNumberLiteral) {
      if (!VALID_NUMBER_LITERAL.matcher(element.getText()).matches()) {
        holder.createErrorAnnotation(element, JsonBundle.message("msg.illegal.floating.point.literal"));
      }
    }
  }

  private static boolean quoteEscaped(String text, int quotePos) {
    int count = 0;
    for (int i = quotePos - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
      count++;
    }
    return count % 2 != 0;
  }
}

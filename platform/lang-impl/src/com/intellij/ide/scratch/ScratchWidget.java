/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.ide.scratch;

import com.intellij.icons.AllIcons;
import com.intellij.lang.Language;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.impl.status.EditorBasedWidget;
import com.intellij.openapi.wm.impl.status.TextPanel;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.ClickListener;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.Consumer;
import com.intellij.util.FileContentUtilCore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;

class ScratchWidget extends EditorBasedWidget implements CustomStatusBarWidget.Multiframe, CustomStatusBarWidget {
  static final String WIDGET_ID = "Scratch";

  private final MyTextPanel myPanel = new MyTextPanel();

  public ScratchWidget(Project project) {
    super(project);
    new ClickListener() {
      @Override
      public boolean onClick(@NotNull MouseEvent e, int clickCount) {
        final Project project = getProject();
        Editor editor = getEditor();
        final LightVirtualFile selectedFile = getScratchFile();
        if (project == null || editor == null || selectedFile == null) return false;

        ListPopup popup = NewScratchFileAction.buildLanguagePopup(project, selectedFile.getLanguage(), new Consumer<Language>() {
          @Override
          public void consume(Language language) {
            selectedFile.setLanguage(NewScratchFileAction.substitute(project, language));
            FileContentUtilCore.reparseFiles(selectedFile);
            update();
          }
        });
        Dimension dimension = popup.getContent().getPreferredSize();
        Point at = new Point(0, -dimension.height);
        popup.show(new RelativePoint(myPanel, at));

        return true;
      }
    }.installOn(myPanel);
  }

  @NotNull
  @Override
  public String ID() {
    return WIDGET_ID;
  }

  @Nullable
  @Override
  public WidgetPresentation getPresentation(@NotNull PlatformType type) {
    return null;
  }

  private void update() {
    LightVirtualFile file = getScratchFile();
    if (file != null) {
      Language lang = file.getLanguage();
      myPanel.setText(lang.getDisplayName());
      myPanel.setBorder(WidgetBorder.INSTANCE);
      myPanel.setIcon(getDefaultIcon(lang));
      myPanel.setVisible(true);
    }
    else {
      myPanel.setBorder(null);
      myPanel.setVisible(false);
    }
    if (myStatusBar != null) {
      myStatusBar.updateWidget(WIDGET_ID);
    }
  }

  @Nullable
  private LightVirtualFile getScratchFile() {
    VirtualFile file = getSelectedFile();
    return file instanceof LightVirtualFile && file.getFileSystem() instanceof ScratchpadFileSystem ? (LightVirtualFile)file : null;
  }

  @Override
  public StatusBarWidget copy() {
    return new ScratchWidget(myProject);
  }

  @Override
  public JComponent getComponent() {
    return myPanel;
  }

  @Override
  public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
    update();
    super.fileOpened(source, file);
  }

  @Override
  public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
    update();
    super.fileClosed(source, file);
  }

  @Override
  public void selectionChanged(@NotNull FileEditorManagerEvent event) {
    update();
    super.selectionChanged(event);
  }

  private static Icon getDefaultIcon(@NotNull Language language) {
    LanguageFileType associatedLanguage = language.getAssociatedFileType();
    return associatedLanguage != null ? associatedLanguage.getIcon() : null;
  }

  private static class MyTextPanel extends TextPanel {
    private int myIconTextGap = 2;
    private Icon myIcon;

    @Override
    protected void paintComponent(@NotNull final Graphics g) {
      super.paintComponent(g);
      if (getText() != null) {
        Rectangle r = getBounds();
        Insets insets = getInsets();
        AllIcons.Ide.Statusbar_arrows.paintIcon(this, g, r.width - insets.right - AllIcons.Ide.Statusbar_arrows.getIconWidth() - 2,
                                                r.height / 2 - AllIcons.Ide.Statusbar_arrows.getIconHeight() / 2);
        if (myIcon != null) {
          myIcon.paintIcon(this, g, insets.left - myIconTextGap - myIcon.getIconWidth(), r.height / 2 - myIcon.getIconHeight() / 2);
        }
      }
    }

    @NotNull
    @Override
    public Insets getInsets() {
      Insets insets = super.getInsets();
      if (myIcon != null) {
        insets.left += myIcon.getIconWidth() + myIconTextGap * 2;
      }
      return insets;
    }

    @Override
    public Dimension getPreferredSize() {
      final Dimension preferredSize = super.getPreferredSize();
      int deltaWidth = AllIcons.Ide.Statusbar_arrows.getIconWidth() + myIconTextGap * 2;
      if (myIcon != null) {
        deltaWidth += myIcon.getIconWidth() + myIconTextGap * 2;
      }
      return new Dimension(preferredSize.width + deltaWidth, preferredSize.height);
    }

    public void setIcon(Icon icon) {
      myIcon = icon;
    }
  }

}

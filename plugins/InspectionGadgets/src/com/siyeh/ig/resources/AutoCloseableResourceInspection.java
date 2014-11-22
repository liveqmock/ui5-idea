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
package com.siyeh.ig.resources;

import com.intellij.codeInspection.ui.ListTable;
import com.intellij.codeInspection.ui.ListWrappingTableModel;
import com.intellij.util.ui.CheckBox;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.ui.UiUtils;

import javax.swing.*;
import java.awt.*;

/**
 * @author Bas Leijdekkers
 */
public class AutoCloseableResourceInspection extends AutoCloseableResourceInspectionBase {

  @Override
  public JComponent createOptionsPanel() {
    final JComponent panel = new JPanel(new BorderLayout());
    final ListTable table =
      new ListTable(new ListWrappingTableModel(ignoredTypes, InspectionGadgetsBundle.message("ignored.autocloseable.types.column.label")));
    final JPanel tablePanel =
      UiUtils.createAddRemoveTreeClassChooserPanel(table, InspectionGadgetsBundle.message("choose.autocloseable.type.to.ignore.title"),
                                                   "java.lang.AutoCloseable");
    final CheckBox checkBox =
      new CheckBox(InspectionGadgetsBundle.message("auto.closeable.resource.returned.option"), this, "ignoreFromMethodCall");
    panel.add(tablePanel, BorderLayout.CENTER);
    panel.add(checkBox, BorderLayout.SOUTH);
    return panel;
  }
}

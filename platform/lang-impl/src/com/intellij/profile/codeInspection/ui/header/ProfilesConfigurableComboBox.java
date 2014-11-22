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
package com.intellij.profile.codeInspection.ui.header;

import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.openapi.util.Comparing;
import com.intellij.profile.Profile;
import com.intellij.ui.ListCellRendererWrapper;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Collection;

/**
 * @author Dmitry Batkovich
 */
public abstract class ProfilesConfigurableComboBox extends JPanel {
  private static final String COMBO_CARD = "combo.card";
  private static final String EDIT_CARD = "edit.card";

  private final JComboBox myProfilesComboBox;
  private final CardLayout myCardLayout;
  private final SaveInputComponent mySubmitNameComponent;
  private final SaveInputComponentValidator.Wrapper mySaveListener;

  public ProfilesConfigurableComboBox(final ListCellRendererWrapper<Profile> comboBoxItemsRenderer) {
    myCardLayout = new CardLayout();
    setLayout(myCardLayout);

    myProfilesComboBox = new JComboBox();
    add(myProfilesComboBox, COMBO_CARD);

    mySaveListener = new SaveInputComponentValidator.Wrapper();
    mySubmitNameComponent = new SaveInputComponent(mySaveListener);
    add(mySubmitNameComponent, EDIT_CARD);

    myProfilesComboBox.setRenderer(comboBoxItemsRenderer);
    myProfilesComboBox.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        final InspectionProfileImpl profile = getSelectedProfile();
        if (profile != null) {
          onProfileChosen(profile);
        }
      }
    });

    showComboBoxCard();
  }

  protected abstract void onProfileChosen(final InspectionProfileImpl inspectionProfile);

  public void showEditCard(final String initialValue, final SaveInputComponentValidator inputValidator) {
    mySaveListener.setDelegate(inputValidator);
    mySubmitNameComponent.setText(initialValue);
    myCardLayout.show(this, EDIT_CARD);
    mySubmitNameComponent.requestFocusToTextField();
  }

  public void reset(final Collection<Profile> profiles) {
    final DefaultComboBoxModel aModel = new DefaultComboBoxModel();
    myProfilesComboBox.setModel(aModel);
    for (Profile profile : profiles) {
      aModel.addElement(profile);
    }
    myProfilesComboBox.setSelectedIndex(0);
  }

  public DefaultComboBoxModel getModel() {
    return (DefaultComboBoxModel)myProfilesComboBox.getModel();
  }

  public InspectionProfileImpl getSelectedProfile() {
    return (InspectionProfileImpl)myProfilesComboBox.getSelectedItem();
  }

  public void selectProfile(InspectionProfileImpl inspectionProfile) {
    myProfilesComboBox.setSelectedItem(inspectionProfile);
  }

  public void selectProfile(String name) {
    for (int i = 0; i < myProfilesComboBox.getItemCount(); i++) {
      if (Comparing.strEqual(((InspectionProfileImpl)myProfilesComboBox.getItemAt(i)).getName(), name)) {
        myProfilesComboBox.setSelectedIndex(i);
        break;
      }
    }
  }

  public void showComboBoxCard() {
    myCardLayout.show(this, COMBO_CARD);
  }
}

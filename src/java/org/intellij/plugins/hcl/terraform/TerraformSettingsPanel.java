/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.intellij.plugins.hcl.terraform;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.ConfigurableUi;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBoxWithWidePopup;
import com.intellij.openapi.ui.TextComponentAccessor;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.Comparing;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.List;

public class TerraformSettingsPanel implements ConfigurableUi<TerraformToolProjectSettings> {
  private final Project myProject;
  private JPanel myWholePanel;
  private TextFieldWithBrowseButton myTerraformPathField;
  private ComboBoxWithWidePopup myMetadataVersionComboBox;

  public TerraformSettingsPanel(Project project) {
    myProject = project;
  }

  @NotNull
  @Override
  public JComponent getComponent() {
    FileChooserDescriptor fileChooserDescriptor = new FileChooserDescriptor(true, false, false, false, false, false);

    myTerraformPathField.addBrowseFolderListener(
        "",
        "Terraform Executable Path",
        null,
        fileChooserDescriptor,
        TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT,
        false
    );

    return myWholePanel;
  }

  @Override
  public boolean isModified(@NotNull TerraformToolProjectSettings settings) {
    return !Comparing.equal(myTerraformPathField.getText(), settings.getTerraformPath())
        || !Comparing.equal(myMetadataVersionComboBox.getSelectedItem(), settings.getMetadataVersion());
  }

  @Override
  public void apply(@NotNull TerraformToolProjectSettings settings) {
    settings.setTerraformPath(myTerraformPathField.getText());
    final Object selected = myMetadataVersionComboBox.getSelectedItem();
    settings.setMetadataVersion(selected != null ? selected.toString() : null);
  }

  @Override
  public void reset(@NotNull TerraformToolProjectSettings settings) {
    myTerraformPathField.setText(settings.getTerraformPath());
    myMetadataVersionComboBox.removeAllItems();
    final String v = settings.getMetadataVersion();
    if (v != null) {
      //noinspection unchecked
      myMetadataVersionComboBox.addItem(v);
      myMetadataVersionComboBox.setSelectedItem(v);
    }
    final List<String> versions = ServiceManager.getService(MetadataVersionsProvider.class).getAvailableVersions(myProject);
    for (String version : versions) {
      //noinspection unchecked
      myMetadataVersionComboBox.addItem(version);
    }
  }
}

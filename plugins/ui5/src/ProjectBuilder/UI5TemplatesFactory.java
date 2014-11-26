package ProjectBuilder;

import Util.UI5Icons;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.platform.ProjectTemplate;
import com.intellij.platform.ProjectTemplatesFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Created by asebak on 9/27/2014.
 * This creates the Main division for the Project Template the getGroups() is the Root level of the template
 */
public class UI5TemplatesFactory extends ProjectTemplatesFactory {
    @NotNull
    @Override
    public String[] getGroups() {
        return new String[]{"OpenUI5"};
    }

    @Override
    public Icon getGroupIcon(String s) {
        return UI5Icons.getIcon();
    }

    @NotNull
    @Override
    public ProjectTemplate[] createTemplates(String s, WizardContext wizardContext) {
        return new ProjectTemplate[]{new UI5ProjectTemplateGenerator()};
    }
}

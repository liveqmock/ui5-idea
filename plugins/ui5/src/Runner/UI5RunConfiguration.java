/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package Runner;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configuration.EmptyRunProfileState;
import com.intellij.execution.configurations.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

import java.net.MalformedURLException;
import java.net.URL;


public class UI5RunConfiguration extends RunConfigurationBase {
    private UI5RunnerParameters myRunnerParameters = new UI5RunnerParameters();

    protected UI5RunConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
    }

    @NotNull
    @Override
    public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new UI5RunConfigurationEditor();
    }

    protected UI5RunnerParameters createRunnerParametersInstance() {
        return new UI5RunnerParameters();
    }

    @Override
    public void readExternal(Element element) throws InvalidDataException {
        super.readExternal(element);
        myRunnerParameters = createRunnerParametersInstance();
        XmlSerializer.deserializeInto(myRunnerParameters, element);
    }

    @Override
    public void writeExternal(Element element) throws WriteExternalException {
        super.writeExternal(element);
        if (myRunnerParameters != null) {
            XmlSerializer.serializeInto(myRunnerParameters, element);
        }
    }

    @Override
    public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment env) throws ExecutionException {
        return EmptyRunProfileState.INSTANCE;
    }

    public static void checkURL(String url) throws RuntimeConfigurationException {
        // check URL for correctness
        try {
            if (url == null) {
                throw new MalformedURLException("No start file specified or this file is invalid");
            }
            new URL(url);
        } catch (MalformedURLException ignored) {
            throw new RuntimeConfigurationError("Incorrect URL");
        }
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        checkURL(myRunnerParameters.getUrl());
    }

    public UI5RunnerParameters getRunnerParameters() {
        return myRunnerParameters;
    }
}
